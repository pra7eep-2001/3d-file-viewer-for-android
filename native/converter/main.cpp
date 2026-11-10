// u3dconv - STEP/IGES -> U3DM tessellated mesh converter built on OCCT 7.6.3.
//
// Usage: u3dconv <input> <output.u3dm> [--quality low|medium|high] [--heal]
//
// stdout: "PROGRESS <0..1>" and "INFO key=value" lines.
// stderr: "ERROR <message>" on failure.
// exit codes: 0 ok, 1 other error, 2 parse/read failure or unsupported content,
//             3 file read but no geometry, 4 I/O error.
//
// See README.md for the U3DM binary format.

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <cstdarg>
#include <cmath>
#include <string>
#include <vector>
#include <map>
#include <memory>
#include <exception>
#include <chrono>

// --- OCCT -----------------------------------------------------------------
#include <Standard_Failure.hxx>
#include <Standard_ErrorHandler.hxx>
#include <OSD.hxx>
#include <Message.hxx>
#include <Message_Messenger.hxx>
#include <Message_Printer.hxx>
#include <Message_ProgressIndicator.hxx>
#include <Message_ProgressScope.hxx>
#include <Message_ProgressRange.hxx>

#include <TopoDS.hxx>
#include <TopoDS_Shape.hxx>
#include <TopoDS_Face.hxx>
#include <TopoDS_Edge.hxx>
#include <TopoDS_Compound.hxx>
#include <TopoDS_Iterator.hxx>
#include <TopExp.hxx>
#include <TopExp_Explorer.hxx>
#include <TopAbs.hxx>
#include <TopLoc_Location.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopTools_IndexedDataMapOfShapeListOfShape.hxx>
#include <TopTools_ListOfShape.hxx>
#include <TopTools_MapOfShape.hxx>
#include <BRep_Builder.hxx>
#include <BRep_Tool.hxx>
#include <BRepAdaptor_Curve.hxx>
#include <BRepBndLib.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <IMeshTools_Parameters.hxx>
#include <Bnd_Box.hxx>
#include <Poly_Triangulation.hxx>
#include <Poly_PolygonOnTriangulation.hxx>
#include <Poly_Polygon3D.hxx>
#include <Geom_Surface.hxx>
#include <GeomLProp_SLProps.hxx>
#include <GCPnts_TangentialDeflection.hxx>
#include <gp_Pnt.hxx>
#include <gp_Vec.hxx>
#include <gp_Dir.hxx>
#include <gp_Trsf.hxx>
#include <ShapeFix_Shape.hxx>
#include <Quantity_Color.hxx>
#include <Quantity_ColorRGBA.hxx>
#include <TCollection_AsciiString.hxx>
#include <TCollection_ExtendedString.hxx>
#include <TCollection_HAsciiString.hxx>
#include <TColStd_SequenceOfAsciiString.hxx>

#include <STEPControl_Reader.hxx>
#include <IGESControl_Reader.hxx>
#include <IGESData_IGESModel.hxx>
#include <IGESData_GlobalSection.hxx>
#include <IFSelect_ReturnStatus.hxx>
#include <Interface_Static.hxx>
#include <XSControl_WorkSession.hxx>
#include <XSControl_TransferReader.hxx>
#include <StepRepr_RepresentationItem.hxx>

#ifndef U3DCONV_NO_XCAF
#include <STEPCAFControl_Reader.hxx>
#include <IGESCAFControl_Reader.hxx>
#include <XCAFApp_Application.hxx>
#include <XCAFDoc_DocumentTool.hxx>
#include <XCAFDoc_ShapeTool.hxx>
#include <XCAFDoc_ColorTool.hxx>
#include <XCAFDoc_ColorType.hxx>
#include <TDocStd_Document.hxx>
#include <TDF_Label.hxx>
#include <TDF_LabelSequence.hxx>
#include <TDataStd_Name.hxx>
#endif

// ---------------------------------------------------------------------------
// Error handling helpers
// ---------------------------------------------------------------------------
enum ExitCode { EXIT_OK = 0, EXIT_OTHER = 1, EXIT_PARSE = 2, EXIT_NOGEOM = 3, EXIT_IO = 4 };

struct ConvError : public std::exception
{
  int code;
  std::string msg;
  ConvError (int c, const std::string& m) : code (c), msg (m) {}
  const char* what() const noexcept override { return msg.c_str(); }
};

static void fail (int code, const std::string& msg) { throw ConvError (code, msg); }

static void info (const char* fmt, ...)
{
  va_list ap;
  va_start (ap, fmt);
  fputs ("INFO ", stdout);
  vfprintf (stdout, fmt, ap);
  fputc ('\n', stdout);
  va_end (ap);
  fflush (stdout);
}

static double g_lastProgress = -1.0;
static void progress (double p)
{
  if (p < 0.0) p = 0.0;
  if (p > 1.0) p = 1.0;
  if (p < g_lastProgress) return;
  g_lastProgress = p;
  printf ("PROGRESS %.2f\n", p);
  fflush (stdout);
}

// Progress indicator mapping OCCT's 0..1 position into [lo,hi].
class ProgressPrinter : public Message_ProgressIndicator
{
public:
  ProgressPrinter (double lo, double hi) : myLo (lo), myHi (hi), myLast (-1.0) {}
  void Show (const Message_ProgressScope&, const Standard_Boolean) override
  {
    double p = myLo + (myHi - myLo) * GetPosition();
    if (p - myLast >= 0.01)
    {
      myLast = p;
      progress (p);
    }
  }
  DEFINE_STANDARD_RTTI_INLINE(ProgressPrinter, Message_ProgressIndicator)
private:
  double myLo, myHi, myLast;
};

// Silence OCCT's own messenger (it would otherwise print to std::cout).
class NullPrinter : public Message_Printer
{
public:
  DEFINE_STANDARD_RTTI_INLINE(NullPrinter, Message_Printer)
protected:
  void send (const TCollection_AsciiString&, const Message_Gravity) const override {}
};

// ---------------------------------------------------------------------------
// Output model
// ---------------------------------------------------------------------------
struct Node
{
  int id = -1;
  int parent = -1;
  std::string name;
  uint8_t r = 0, g = 0, b = 0, a = 0;
  std::vector<float> verts;     // xyz
  std::vector<float> normals;   // xyz (same count as verts) or empty
  std::vector<uint32_t> tris;   // multiple of 3
  std::vector<float> edgeVerts; // xyz
  std::vector<uint32_t> edges;  // pairs
};

struct Model
{
  std::vector<std::unique_ptr<Node>> nodes;
  bool hasColours = false;
  Node* add (int parent, const std::string& name)
  {
    nodes.emplace_back (new Node());
    Node* n = nodes.back().get();
    n->id = (int)nodes.size() - 1;
    n->parent = parent;
    n->name = name;
    return n;
  }
};

// ---------------------------------------------------------------------------
// Geometry extraction
// ---------------------------------------------------------------------------
static inline void pushPnt (std::vector<float>& v, const gp_Pnt& p)
{
  v.push_back ((float)p.X());
  v.push_back ((float)p.Y());
  v.push_back ((float)p.Z());
}

// Fill triangle mesh and edge polylines of a shape into a node.
static void extractGeometry (const TopoDS_Shape& shape, Node& node)
{
  // Map from (face index) -> vertex offset in node.verts, to build edge
  // polylines from the triangulation nodes later on.
  TopTools_IndexedMapOfShape faceMap;
  TopExp::MapShapes (shape, TopAbs_FACE, faceMap);
  std::vector<uint32_t> faceOffset (faceMap.Extent() + 1, 0);
  std::vector<char> faceHasTri (faceMap.Extent() + 1, 0);

  bool allNormals = true;
  for (int fi = 1; fi <= faceMap.Extent(); ++fi)
  {
    const TopoDS_Face& face = TopoDS::Face (faceMap (fi));
    TopLoc_Location loc;
    Handle(Poly_Triangulation) tri = BRep_Tool::Triangulation (face, loc);
    if (tri.IsNull() || tri->NbTriangles() == 0 || tri->NbNodes() == 0)
      continue;

    const bool reversed = (face.Orientation() == TopAbs_REVERSED);
    const gp_Trsf trsf = loc.Transformation();
    const bool hasTrsf = (loc.IsIdentity() == Standard_False);
    const uint32_t base = (uint32_t)(node.verts.size() / 3);
    faceOffset[fi] = base;
    faceHasTri[fi] = 1;
    const int nbNodes = tri->NbNodes();
    const int nbTris = tri->NbTriangles();

    // vertices
    node.verts.reserve (node.verts.size() + 3 * nbNodes);
    for (int i = 1; i <= nbNodes; ++i)
    {
      gp_Pnt p = tri->Node (i);
      if (hasTrsf) p.Transform (trsf);
      pushPnt (node.verts, p);
    }

    // triangles
    node.tris.reserve (node.tris.size() + 3 * nbTris);
    for (int i = 1; i <= nbTris; ++i)
    {
      int n1, n2, n3;
      tri->Triangle (i).Get (n1, n2, n3);
      if (n1 < 1 || n2 < 1 || n3 < 1 || n1 > nbNodes || n2 > nbNodes || n3 > nbNodes) continue;
      if (reversed) std::swap (n2, n3);
      node.tris.push_back (base + (uint32_t)(n1 - 1));
      node.tris.push_back (base + (uint32_t)(n2 - 1));
      node.tris.push_back (base + (uint32_t)(n3 - 1));
    }

    // normals: exact surface normals at UV nodes when possible
    std::vector<float> fn (3 * nbNodes, 0.0f);
    std::vector<char> ok (nbNodes, 0);
    bool anyExact = false;
    if (tri->HasUVNodes())
    {
      TopLoc_Location surfLoc;
      Handle(Geom_Surface) surf = BRep_Tool::Surface (face, surfLoc);
      if (!surf.IsNull())
      {
        const gp_Trsf surfTrsf = surfLoc.Transformation();
        const bool hasSurfTrsf = (surfLoc.IsIdentity() == Standard_False);
        GeomLProp_SLProps props (surf, 1, 1e-9);
        for (int i = 1; i <= nbNodes; ++i)
        {
          const gp_Pnt2d uv = tri->UVNode (i);
          props.SetParameters (uv.X(), uv.Y());
          if (!props.IsNormalDefined()) continue;
          gp_Dir n = props.Normal();
          if (reversed) n.Reverse();
          if (hasSurfTrsf) n.Transform (surfTrsf);
          fn[3 * (i - 1) + 0] = (float)n.X();
          fn[3 * (i - 1) + 1] = (float)n.Y();
          fn[3 * (i - 1) + 2] = (float)n.Z();
          ok[i - 1] = 1;
          anyExact = true;
        }
      }
    }
    // fallback: accumulated area-weighted triangle normals for undefined nodes
    bool needFallback = false;
    for (int i = 0; i < nbNodes; ++i) if (!ok[i]) { needFallback = true; break; }
    if (needFallback)
    {
      std::vector<double> acc (3 * nbNodes, 0.0);
      const size_t triStart = node.tris.size() - 3 * (size_t)nbTris;
      for (size_t t = triStart; t + 2 < node.tris.size(); t += 3)
      {
        const uint32_t i0 = node.tris[t] - base, i1 = node.tris[t + 1] - base, i2 = node.tris[t + 2] - base;
        const float* p0 = &node.verts[3 * (base + i0)];
        const float* p1 = &node.verts[3 * (base + i1)];
        const float* p2 = &node.verts[3 * (base + i2)];
        const double ax = p1[0] - p0[0], ay = p1[1] - p0[1], az = p1[2] - p0[2];
        const double bx = p2[0] - p0[0], by = p2[1] - p0[1], bz = p2[2] - p0[2];
        const double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        for (uint32_t k : { i0, i1, i2 })
        {
          acc[3 * k] += nx; acc[3 * k + 1] += ny; acc[3 * k + 2] += nz;
        }
      }
      for (int i = 0; i < nbNodes; ++i)
      {
        if (ok[i]) continue;
        double nx = acc[3 * i], ny = acc[3 * i + 1], nz = acc[3 * i + 2];
        double len = std::sqrt (nx * nx + ny * ny + nz * nz);
        if (len > 1e-20) { nx /= len; ny /= len; nz /= len; }
        else { nx = 0; ny = 0; nz = 1; }
        fn[3 * i] = (float)nx; fn[3 * i + 1] = (float)ny; fn[3 * i + 2] = (float)nz;
      }
    }
    (void)anyExact;
    node.normals.insert (node.normals.end(), fn.begin(), fn.end());
  }
  (void)allNormals;

  // Edge polylines
  TopTools_IndexedMapOfShape edgeMap;
  TopExp::MapShapes (shape, TopAbs_EDGE, edgeMap);
  TopTools_IndexedDataMapOfShapeListOfShape edgeFaces;
  TopExp::MapShapesAndAncestors (shape, TopAbs_EDGE, TopAbs_FACE, edgeFaces);

  for (int ei = 1; ei <= edgeMap.Extent(); ++ei)
  {
    const TopoDS_Edge& edge = TopoDS::Edge (edgeMap (ei));
    if (BRep_Tool::Degenerated (edge)) continue;
    std::vector<gp_Pnt> pts;
    bool done = false;

    // 1. polygon on triangulation of an adjacent face
    const int efIdx = edgeFaces.FindIndex (edge);
    if (efIdx > 0)
    {
      const TopTools_ListOfShape& faces = edgeFaces (efIdx);
      for (TopTools_ListOfShape::Iterator it (faces); it.More() && !done; it.Next())
      {
        const TopoDS_Face& face = TopoDS::Face (it.Value());
        TopLoc_Location loc;
        Handle(Poly_Triangulation) tri = BRep_Tool::Triangulation (face, loc);
        if (tri.IsNull()) continue;
        Handle(Poly_PolygonOnTriangulation) pot = BRep_Tool::PolygonOnTriangulation (edge, tri, loc);
        if (pot.IsNull() || pot->NbNodes() < 2) continue;
        const TColStd_Array1OfInteger& idx = pot->Nodes();
        const gp_Trsf trsf = loc.Transformation();
        const bool hasTrsf = (loc.IsIdentity() == Standard_False);
        pts.reserve (idx.Length());
        for (int k = idx.Lower(); k <= idx.Upper(); ++k)
        {
          const int ni = idx (k);
          if (ni < 1 || ni > tri->NbNodes()) { pts.clear(); break; }
          gp_Pnt p = tri->Node (ni);
          if (hasTrsf) p.Transform (trsf);
          pts.push_back (p);
        }
        done = pts.size() >= 2;
      }
    }
    // 2. free 3D polygon
    if (!done)
    {
      TopLoc_Location loc;
      Handle(Poly_Polygon3D) poly = BRep_Tool::Polygon3D (edge, loc);
      if (!poly.IsNull() && poly->NbNodes() >= 2)
      {
        const TColgp_Array1OfPnt& pn = poly->Nodes();
        const gp_Trsf trsf = loc.Transformation();
        const bool hasTrsf = (loc.IsIdentity() == Standard_False);
        for (int k = pn.Lower(); k <= pn.Upper(); ++k)
        {
          gp_Pnt p = pn (k);
          if (hasTrsf) p.Transform (trsf);
          pts.push_back (p);
        }
        done = pts.size() >= 2;
      }
    }
    // 3. sample the curve
    if (!done)
    {
      try
      {
        BRepAdaptor_Curve curve (edge);
        GCPnts_TangentialDeflection disc (curve, 0.3, 0.1, 2);
        for (int k = 1; k <= disc.NbPoints(); ++k) pts.push_back (disc.Value (k));
        done = pts.size() >= 2;
      }
      catch (Standard_Failure const&) { pts.clear(); done = false; }
    }
    if (!done) continue;

    const uint32_t base = (uint32_t)(node.edgeVerts.size() / 3);
    for (const gp_Pnt& p : pts) pushPnt (node.edgeVerts, p);
    for (size_t k = 0; k + 1 < pts.size(); ++k)
    {
      node.edges.push_back (base + (uint32_t)k);
      node.edges.push_back (base + (uint32_t)k + 1);
    }
  }
}

static int countSolids (const TopoDS_Shape& s)
{
  int n = 0;
  for (TopExp_Explorer ex (s, TopAbs_SOLID); ex.More(); ex.Next()) ++n;
  return n;
}

// Create geometry for a leaf shape under 'parent': if the shape holds several
// solids, one child per solid (plus one for faces outside any solid), else the
// leaf itself holds the mesh.
static void addLeafGeometry (Model& model, Node* leaf, const TopoDS_Shape& shape)
{
  const int nbSolids = countSolids (shape);
  if (nbSolids <= 1)
  {
    extractGeometry (shape, *leaf);
    return;
  }
  TopTools_MapOfShape facesInSolids;
  int k = 0;
  for (TopExp_Explorer ex (shape, TopAbs_SOLID); ex.More(); ex.Next())
  {
    ++k;
    char buf[64];
    snprintf (buf, sizeof (buf), "Solid %d", k);
    Node* child = model.add (leaf->id, buf);
    child->r = leaf->r; child->g = leaf->g; child->b = leaf->b; child->a = leaf->a;
    extractGeometry (ex.Current(), *child);
    for (TopExp_Explorer fx (ex.Current(), TopAbs_FACE); fx.More(); fx.Next())
      facesInSolids.Add (fx.Current());
  }
  // faces not belonging to a solid
  TopoDS_Compound rest;
  BRep_Builder bb;
  bb.MakeCompound (rest);
  bool any = false;
  for (TopExp_Explorer fx (shape, TopAbs_FACE); fx.More(); fx.Next())
  {
    if (!facesInSolids.Contains (fx.Current()))
    {
      bb.Add (rest, fx.Current());
      any = true;
    }
  }
  if (any)
  {
    Node* child = model.add (leaf->id, "Free faces");
    child->r = leaf->r; child->g = leaf->g; child->b = leaf->b; child->a = leaf->a;
    extractGeometry (rest, *child);
  }
}

// ---------------------------------------------------------------------------
// Input detection
// ---------------------------------------------------------------------------
enum Format { FMT_UNKNOWN, FMT_STEP, FMT_IGES };

static std::string lower (std::string s)
{
  for (char& c : s) c = (char)tolower ((unsigned char)c);
  return s;
}

static Format formatFromExtension (const std::string& path)
{
  size_t dot = path.find_last_of ('.');
  if (dot == std::string::npos) return FMT_UNKNOWN;
  std::string ext = lower (path.substr (dot + 1));
  if (ext == "step" || ext == "stp" || ext == "p21" || ext == "stpz") return FMT_STEP;
  if (ext == "iges" || ext == "igs") return FMT_IGES;
  return FMT_UNKNOWN;
}

struct Sniff
{
  Format fmt = FMT_UNKNOWN;
  bool stepHasEnd = true;
  size_t size = 0;
};

static Sniff sniffFile (const std::string& path)
{
  Sniff s;
  FILE* f = fopen (path.c_str(), "rb");
  if (!f) fail (EXIT_IO, "cannot open input file: " + path);
  std::vector<char> head (4096, 0);
  size_t n = fread (head.data(), 1, head.size() - 1, f);
  head[n] = 0;
  fseek (f, 0, SEEK_END);
  long sz = ftell (f);
  s.size = sz > 0 ? (size_t)sz : 0;
  std::string h (head.data(), n);
  if (h.find ("ISO-10303-21") != std::string::npos)
  {
    s.fmt = FMT_STEP;
    // the file must end with END-ISO-10303-21; (allow trailing whitespace)
    std::vector<char> tail (4096, 0);
    long off = sz > 4095 ? sz - 4095 : 0;
    fseek (f, off, SEEK_SET);
    size_t m = fread (tail.data(), 1, 4095, f);
    std::string t (tail.data(), m);
    s.stepHasEnd = (t.find ("END-ISO-10303-21") != std::string::npos);
  }
  else
  {
    // IGES: 'S' in column 73 of one of the first 80-char records
    for (size_t line = 0; line < 4 && (line + 1) * 80 <= n; ++line)
    {
      bool ascii = true;
      for (size_t k = 0; k < 72; ++k)
      {
        unsigned char c = (unsigned char)head[line * 80 + k];
        if (c < 9 || c > 126) { ascii = false; break; }
      }
      if (ascii && head[line * 80 + 72] == 'S') { s.fmt = FMT_IGES; break; }
    }
    if (s.fmt == FMT_UNKNOWN)
    {
      // line-based check in case of CRLF line endings
      size_t pos = 0;
      for (int line = 0; line < 4 && pos < n; ++line)
      {
        size_t eol = h.find ('\n', pos);
        if (eol == std::string::npos) eol = n;
        size_t len = eol - pos;
        if (len > 0 && h[pos + len - 1] == '\r') --len;
        if (len >= 73 && h[pos + 72] == 'S') { s.fmt = FMT_IGES; break; }
        pos = eol + 1;
      }
    }
  }
  fclose (f);
  return s;
}

// ---------------------------------------------------------------------------
// Readers
// ---------------------------------------------------------------------------
struct Options
{
  std::string input, output;
  int quality = 1; // 0 low, 1 medium, 2 high
  bool heal = false;
};

static std::string toUtf8 (const TCollection_ExtendedString& s)
{
  Standard_Integer n = s.LengthOfCString();
  if (n <= 0) return std::string();
  std::vector<char> buf (n + 1, 0);
  Standard_PCharacter p = buf.data();
  s.ToUTF8CString (p);
  return std::string (buf.data());
}

static std::string trim (const std::string& s)
{
  size_t b = s.find_first_not_of (" \t\r\n");
  if (b == std::string::npos) return std::string();
  size_t e = s.find_last_not_of (" \t\r\n");
  return s.substr (b, e - b + 1);
}

static void reportStepUnits (STEPControl_Reader& reader)
{
  try
  {
    TColStd_SequenceOfAsciiString lenUnits, angUnits, solidUnits;
    reader.FileUnits (lenUnits, angUnits, solidUnits);
    std::string u;
    for (int i = 1; i <= lenUnits.Length(); ++i)
    {
      if (!u.empty()) u += ",";
      u += lenUnits (i).ToCString();
    }
    info ("fileUnit=%s", u.empty() ? "unknown" : u.c_str());
  }
  catch (...) { info ("fileUnit=unknown"); }
}

static void reportIgesUnits (IGESControl_Reader& reader)
{
  try
  {
    Handle(IGESData_IGESModel) model = reader.IGESModel();
    std::string u;
    if (!model.IsNull())
    {
      Handle(TCollection_HAsciiString) un = model->GlobalSection().UnitName();
      if (!un.IsNull()) u = trim (un->ToCString());
      if (u.empty())
      {
        static const char* names[] = { "", "INCH", "MM", "?", "FT", "MI", "M", "KM", "MIL", "UM", "CM", "UIN" };
        int flag = model->GlobalSection().UnitFlag();
        if (flag >= 1 && flag <= 11) u = names[flag];
      }
    }
    info ("fileUnit=%s", u.empty() ? "unknown" : u.c_str());
  }
  catch (...) { info ("fileUnit=unknown"); }
}

static const char* statusText (IFSelect_ReturnStatus st)
{
  switch (st)
  {
    case IFSelect_RetVoid: return "nothing read (empty or unrecognised file)";
    case IFSelect_RetDone: return "done";
    case IFSelect_RetError: return "syntax error in file";
    case IFSelect_RetFail: return "file could not be read";
    case IFSelect_RetStop: return "read stopped";
  }
  return "unknown status";
}

// Shapes to mesh + node tree construction happen in two phases: first we
// collect leaves (node + shape) so that meshing can run once on a compound of
// everything, then geometry is extracted.
struct Leaf
{
  Node* node;
  TopoDS_Shape shape;
};

#ifndef U3DCONV_NO_XCAF
// ----- XCAF path ------------------------------------------------------------
static std::string labelName (const TDF_Label& label)
{
  Handle(TDataStd_Name) nameAttr;
  if (!label.IsNull() && label.FindAttribute (TDataStd_Name::GetID(), nameAttr))
    return trim (toUtf8 (nameAttr->Get()));
  return std::string();
}

static bool labelColour (const Handle(XCAFDoc_ColorTool)& ct, const TDF_Label& label,
                         const TDF_Label& refLabel, const TopoDS_Shape& shape, Quantity_ColorRGBA& out)
{
  if (ct.IsNull()) return false;
  const XCAFDoc_ColorType types[] = { XCAFDoc_ColorSurf, XCAFDoc_ColorGen, XCAFDoc_ColorCurv };
  for (XCAFDoc_ColorType t : types)
    if (!label.IsNull() && ct->GetColor (label, t, out)) return true;
  if (!refLabel.IsNull() && !refLabel.IsEqual (label))
    for (XCAFDoc_ColorType t : types)
      if (ct->GetColor (refLabel, t, out)) return true;
  // first face colour found inside the shape
  if (!shape.IsNull())
  {
    for (TopExp_Explorer ex (shape, TopAbs_FACE); ex.More(); ex.Next())
    {
      Quantity_Color c;
      if (ct->GetColor (ex.Current(), XCAFDoc_ColorSurf, c) || ct->GetColor (ex.Current(), XCAFDoc_ColorGen, c))
      {
        out = Quantity_ColorRGBA (c, 1.0f);
        return true;
      }
    }
  }
  return false;
}

static void setColour (Node& n, const Quantity_ColorRGBA& c, Model& model)
{
  auto to8 = [] (double v) { int i = (int)std::lround (v * 255.0); return (uint8_t)(i < 0 ? 0 : (i > 255 ? 255 : i)); };
  Quantity_Color rgb = c.GetRGB();
  n.r = to8 (rgb.Red()); n.g = to8 (rgb.Green()); n.b = to8 (rgb.Blue());
  uint8_t a = to8 (c.Alpha());
  n.a = a == 0 ? 1 : a; // alpha 0 would mean "no colour"
  model.hasColours = true;
}

struct XcafWalker
{
  Model& model;
  Handle(XCAFDoc_ShapeTool) st;
  Handle(XCAFDoc_ColorTool) ct;
  std::vector<Leaf>& leaves;
  int partCounter = 0;
  int depth = 0;

  XcafWalker (Model& m, const Handle(XCAFDoc_ShapeTool)& s, const Handle(XCAFDoc_ColorTool)& c, std::vector<Leaf>& l)
  : model (m), st (s), ct (c), leaves (l) {}

  void walk (const TDF_Label& label, int parentId, const TopLoc_Location& parentLoc)
  {
    if (depth > 64) return; // cyclic reference guard
    TDF_Label ref = label;
    TopLoc_Location loc = parentLoc;
    if (XCAFDoc_ShapeTool::IsReference (label))
    {
      loc = parentLoc * XCAFDoc_ShapeTool::GetLocation (label);
      TDF_Label r;
      if (XCAFDoc_ShapeTool::GetReferredShape (label, r)) ref = r;
    }
    std::string name = labelName (label);
    if (name.empty() && !ref.IsEqual (label)) name = labelName (ref);
    if (name.empty())
    {
      char buf[64];
      snprintf (buf, sizeof (buf), "Part %d", ++partCounter);
      name = buf;
    }
    Node* node = model.add (parentId, name);

    TopoDS_Shape shape;
    XCAFDoc_ShapeTool::GetShape (ref, shape);
    Quantity_ColorRGBA col;
    if (labelColour (ct, label, ref, shape, col)) setColour (*node, col, model);

    if (XCAFDoc_ShapeTool::IsAssembly (ref))
    {
      TDF_LabelSequence comps;
      XCAFDoc_ShapeTool::GetComponents (ref, comps);
      ++depth;
      for (int i = 1; i <= comps.Length(); ++i) walk (comps (i), node->id, loc);
      --depth;
    }
    else if (!shape.IsNull())
    {
      TopoDS_Shape located = shape;
      if (!loc.IsIdentity()) located = shape.Moved (loc);
      leaves.push_back (Leaf{ node, located });
    }
  }
};

static void readXcaf (const Options& opt, Format fmt, Model& model, std::vector<Leaf>& leaves)
{
  Handle(XCAFApp_Application) app = XCAFApp_Application::GetApplication();
  Handle(TDocStd_Document) doc;
  app->NewDocument ("MDTV-XCAF", doc);

  progress (0.05);
  Handle(ProgressPrinter) prog = new ProgressPrinter (0.10, 0.50);
  if (fmt == FMT_STEP)
  {
    STEPCAFControl_Reader reader;
    reader.SetColorMode (true);
    reader.SetNameMode (true);
    reader.SetLayerMode (true);
    IFSelect_ReturnStatus st = reader.ReadFile (opt.input.c_str());
    if (st != IFSelect_RetDone) fail (EXIT_PARSE, std::string ("STEP read failed: ") + statusText (st));
    progress (0.10);
    reportStepUnits (reader.ChangeReader());
    if (reader.NbRootsForTransfer() <= 0) fail (EXIT_NOGEOM, "STEP file contains no transferable roots");
    if (!reader.Transfer (doc, prog->Start())) fail (EXIT_PARSE, "STEP transfer to document failed");
  }
  else
  {
    IGESCAFControl_Reader reader;
    reader.SetColorMode (true);
    reader.SetNameMode (true);
    reader.SetLayerMode (true);
    IFSelect_ReturnStatus st = reader.ReadFile (opt.input.c_str());
    if (st != IFSelect_RetDone) fail (EXIT_PARSE, std::string ("IGES read failed: ") + statusText (st));
    progress (0.10);
    reportIgesUnits (reader);
    if (reader.NbRootsForTransfer() <= 0) fail (EXIT_NOGEOM, "IGES file contains no transferable roots");
    if (!reader.Transfer (doc, prog->Start())) fail (EXIT_PARSE, "IGES transfer to document failed");
  }
  progress (0.50);

  Handle(XCAFDoc_ShapeTool) st = XCAFDoc_DocumentTool::ShapeTool (doc->Main());
  Handle(XCAFDoc_ColorTool) ct = XCAFDoc_DocumentTool::ColorTool (doc->Main());
  TDF_LabelSequence free;
  st->GetFreeShapes (free);
  XcafWalker w (model, st, ct, leaves);
  for (int i = 1; i <= free.Length(); ++i) w.walk (free (i), -1, TopLoc_Location());
}
#endif

// ----- plain reader path ------------------------------------------------------
static std::string stepItemName (const Handle(XSControl_WorkSession)& ws, const TopoDS_Shape& s)
{
  try
  {
    if (ws.IsNull()) return std::string();
    Handle(XSControl_TransferReader) tr = ws->TransferReader();
    if (tr.IsNull()) return std::string();
    Handle(Standard_Transient) ent = tr->EntityFromShapeResult (s, 1);
    if (ent.IsNull()) ent = tr->EntityFromShapeResult (s, -1);
    Handle(StepRepr_RepresentationItem) item = Handle(StepRepr_RepresentationItem)::DownCast (ent);
    if (!item.IsNull() && !item->Name().IsNull()) return trim (item->Name()->ToCString());
  }
  catch (...) {}
  return std::string();
}

static void readPlain (const Options& opt, Format fmt, Model& model, std::vector<Leaf>& leaves)
{
  progress (0.05);
  Handle(ProgressPrinter) prog = new ProgressPrinter (0.10, 0.50);
  TopoDS_Shape all;
  Handle(XSControl_WorkSession) ws;
  if (fmt == FMT_STEP)
  {
    STEPControl_Reader reader;
    IFSelect_ReturnStatus st = reader.ReadFile (opt.input.c_str());
    if (st != IFSelect_RetDone) fail (EXIT_PARSE, std::string ("STEP read failed: ") + statusText (st));
    progress (0.10);
    reportStepUnits (reader);
    if (reader.NbRootsForTransfer() <= 0) fail (EXIT_NOGEOM, "STEP file contains no transferable roots");
    reader.TransferRoots (prog->Start());
    all = reader.OneShape();
    ws = reader.WS();
  }
  else
  {
    IGESControl_Reader reader;
    IFSelect_ReturnStatus st = reader.ReadFile (opt.input.c_str());
    if (st != IFSelect_RetDone) fail (EXIT_PARSE, std::string ("IGES read failed: ") + statusText (st));
    progress (0.10);
    reportIgesUnits (reader);
    if (reader.NbRootsForTransfer() <= 0) fail (EXIT_NOGEOM, "IGES file contains no transferable roots");
    reader.TransferRoots (prog->Start());
    all = reader.OneShape();
  }
  progress (0.50);
  if (all.IsNull()) return;

  std::vector<TopoDS_Shape> tops;
  if (all.ShapeType() == TopAbs_COMPOUND)
    for (TopoDS_Iterator it (all); it.More(); it.Next()) tops.push_back (it.Value());
  else
    tops.push_back (all);

  int counter = 0;
  for (const TopoDS_Shape& top : tops)
  {
    std::string name = fmt == FMT_STEP ? stepItemName (ws, top) : std::string();
    if (name.empty())
    {
      char buf[64];
      snprintf (buf, sizeof (buf), "Part %d", ++counter);
      name = buf;
    }
    Node* node = model.add (-1, name);
    leaves.push_back (Leaf{ node, top });
  }
}

// ---------------------------------------------------------------------------
// Writer
// ---------------------------------------------------------------------------
struct Writer
{
  FILE* f;
  std::vector<uint8_t> buf;
  explicit Writer (FILE* file) : f (file) { buf.reserve (1 << 20); }
  void flush()
  {
    if (!buf.empty())
    {
      if (fwrite (buf.data(), 1, buf.size(), f) != buf.size()) fail (EXIT_IO, "write error");
      buf.clear();
    }
  }
  void raw (const void* p, size_t n)
  {
    const uint8_t* b = (const uint8_t*)p;
    if (buf.size() + n > (1 << 20)) flush();
    if (n > (1 << 20)) { if (fwrite (b, 1, n, f) != n) fail (EXIT_IO, "write error"); return; }
    buf.insert (buf.end(), b, b + n);
  }
  void u8 (uint8_t v) { raw (&v, 1); }
  void u32 (uint32_t v) { uint8_t b[4] = { (uint8_t)v, (uint8_t)(v >> 8), (uint8_t)(v >> 16), (uint8_t)(v >> 24) }; raw (b, 4); }
  void i32 (int32_t v) { u32 ((uint32_t)v); }
  void f64 (double v) { uint64_t u; memcpy (&u, &v, 8); uint8_t b[8]; for (int i = 0; i < 8; ++i) b[i] = (uint8_t)(u >> (8 * i)); raw (b, 8); }
  void f32s (const std::vector<float>& v)
  {
    // little-endian host assumed for the bulk arrays (x86_64 / aarch64)
    raw (v.data(), v.size() * sizeof (float));
  }
  void u32s (const std::vector<uint32_t>& v) { raw (v.data(), v.size() * sizeof (uint32_t)); }
};

static void writeModel (const Model& model, const std::string& path)
{
  FILE* f = fopen (path.c_str(), "wb");
  if (!f) fail (EXIT_IO, "cannot create output file: " + path);
  try
  {
    Writer w (f);
    w.raw ("U3DM", 4);
    w.u32 (1);
    w.u32 (model.hasColours ? 1u : 0u);
    w.f64 (1.0);
    w.u32 ((uint32_t)model.nodes.size());
    size_t count = 0;
    for (const auto& np : model.nodes)
    {
      const Node& n = *np;
      w.i32 (n.id);
      w.i32 (n.parent);
      w.u32 ((uint32_t)n.name.size());
      w.raw (n.name.data(), n.name.size());
      w.u8 (n.r); w.u8 (n.g); w.u8 (n.b); w.u8 (n.a);
      const uint32_t vc = (uint32_t)(n.verts.size() / 3);
      w.u32 (vc);
      w.f32s (n.verts);
      const bool hasN = !n.normals.empty() && n.normals.size() == n.verts.size();
      w.u8 (hasN ? 1 : 0);
      if (hasN) w.f32s (n.normals);
      w.u32 ((uint32_t)n.tris.size());
      w.u32s (n.tris);
      w.u32 ((uint32_t)(n.edgeVerts.size() / 3));
      w.f32s (n.edgeVerts);
      w.u32 ((uint32_t)n.edges.size());
      w.u32s (n.edges);
      ++count;
      if ((count & 15) == 0) progress (0.90 + 0.10 * (double)count / (double)model.nodes.size());
    }
    w.flush();
  }
  catch (...)
  {
    fclose (f);
    throw;
  }
  if (fclose (f) != 0) fail (EXIT_IO, "write error on close");
}

// ---------------------------------------------------------------------------
static void usage()
{
  fprintf (stderr, "usage: u3dconv <input.step|.iges> <output.u3dm> [--quality low|medium|high] [--heal]\n");
}

static int run (const Options& opt)
{
  auto t0 = std::chrono::steady_clock::now();

  // --- detect format
  Sniff sn = sniffFile (opt.input);
  Format fmt = sn.fmt;
  Format extFmt = formatFromExtension (opt.input);
  if (fmt == FMT_UNKNOWN) fmt = extFmt;
  if (fmt == FMT_UNKNOWN) fail (EXIT_PARSE, "unrecognised input format (expected STEP or IGES)");
  if (sn.size == 0) fail (EXIT_PARSE, "input file is empty");
  if (fmt == FMT_STEP && sn.fmt == FMT_STEP && !sn.stepHasEnd)
    fail (EXIT_PARSE, "STEP file is truncated (missing END-ISO-10303-21)");
  info ("format=%s", fmt == FMT_STEP ? "STEP" : "IGES");

  // --- read
  Model model;
  std::vector<Leaf> leaves;
#ifndef U3DCONV_NO_XCAF
  info ("xcaf=1");
  readXcaf (opt, fmt, model, leaves);
#else
  info ("xcaf=0");
  readPlain (opt, fmt, model, leaves);
#endif

  // --- collect shapes
  BRep_Builder bb;
  TopoDS_Compound all;
  bb.MakeCompound (all);
  size_t nbFaces = 0, nbEdges = 0;
  for (Leaf& l : leaves)
  {
    if (l.shape.IsNull()) continue;
    if (opt.heal)
    {
      try
      {
        ShapeFix_Shape fix (l.shape);
        fix.Perform();
        TopoDS_Shape fixed = fix.Shape();
        if (!fixed.IsNull()) l.shape = fixed;
      }
      catch (Standard_Failure const&) {}
    }
    bb.Add (all, l.shape);
    for (TopExp_Explorer ex (l.shape, TopAbs_FACE); ex.More(); ex.Next()) ++nbFaces;
    for (TopExp_Explorer ex (l.shape, TopAbs_EDGE); ex.More(); ex.Next()) ++nbEdges;
  }
  if (opt.heal) info ("heal=1");
  info ("faces=%zu edges=%zu", nbFaces, nbEdges);
  if (nbFaces == 0 && nbEdges == 0) fail (EXIT_NOGEOM, "file was read but contains no geometry");

  // --- mesh
  Bnd_Box box;
  BRepBndLib::Add (all, box, false);
  double diag = 0.0;
  if (!box.IsVoid())
  {
    double xmin, ymin, zmin, xmax, ymax, zmax;
    box.Get (xmin, ymin, zmin, xmax, ymax, zmax);
    diag = std::sqrt ((xmax - xmin) * (xmax - xmin) + (ymax - ymin) * (ymax - ymin) + (zmax - zmin) * (zmax - zmin));
    info ("bbox=%.6g,%.6g,%.6g,%.6g,%.6g,%.6g", xmin, ymin, zmin, xmax, ymax, zmax);
  }
  static const double linFactor[3] = { 0.004, 0.0015, 0.0005 };
  static const double angDefl[3] = { 0.5, 0.3, 0.15 };
  double linDefl = diag * linFactor[opt.quality];
  if (linDefl < 1e-4) linDefl = 1e-4;
  info ("quality=%s linDefl=%.6g angDefl=%.3g diag=%.6g", opt.quality == 0 ? "low" : (opt.quality == 2 ? "high" : "medium"), linDefl, angDefl[opt.quality], diag);

  if (nbFaces > 0)
  {
    Handle(ProgressPrinter) prog = new ProgressPrinter (0.50, 0.88);
    IMeshTools_Parameters params;
    params.Deflection = linDefl;
    params.Angle = angDefl[opt.quality];
    params.Relative = Standard_False;
    params.InParallel = Standard_True;
    BRepMesh_IncrementalMesh mesher (all, params, prog->Start());
    (void)mesher;
  }
  progress (0.88);

  // --- extract
  for (Leaf& l : leaves)
  {
    if (l.shape.IsNull()) continue;
    addLeafGeometry (model, l.node, l.shape);
  }
  progress (0.90);

  size_t totalV = 0, totalT = 0, totalEV = 0, totalE = 0;
  for (const auto& n : model.nodes)
  {
    totalV += n->verts.size() / 3;
    totalT += n->tris.size() / 3;
    totalEV += n->edgeVerts.size() / 3;
    totalE += n->edges.size() / 2;
  }
  if (totalT == 0 && totalE == 0) fail (EXIT_NOGEOM, "no triangles or edges could be generated");
  info ("nodes=%zu triangles=%zu vertices=%zu edgeVertices=%zu edgeSegments=%zu colours=%d",
        model.nodes.size(), totalT, totalV, totalEV, totalE, model.hasColours ? 1 : 0);

  // --- write
  writeModel (model, opt.output);
  progress (1.0);
  auto t1 = std::chrono::steady_clock::now();
  info ("timeMs=%lld", (long long)std::chrono::duration_cast<std::chrono::milliseconds> (t1 - t0).count());
  return EXIT_OK;
}

int main (int argc, char** argv)
{
  Options opt;
  std::vector<std::string> pos;
  for (int i = 1; i < argc; ++i)
  {
    std::string a = argv[i];
    if (a == "--quality")
    {
      if (i + 1 >= argc) { usage(); return EXIT_OTHER; }
      std::string q = lower (argv[++i]);
      if (q == "low") opt.quality = 0;
      else if (q == "medium") opt.quality = 1;
      else if (q == "high") opt.quality = 2;
      else { fprintf (stderr, "ERROR unknown quality '%s'\n", q.c_str()); return EXIT_OTHER; }
    }
    else if (a.rfind ("--quality=", 0) == 0)
    {
      std::string q = lower (a.substr (10));
      if (q == "low") opt.quality = 0;
      else if (q == "medium") opt.quality = 1;
      else if (q == "high") opt.quality = 2;
      else { fprintf (stderr, "ERROR unknown quality '%s'\n", q.c_str()); return EXIT_OTHER; }
    }
    else if (a == "--heal") opt.heal = true;
    else if (a == "-h" || a == "--help") { usage(); return EXIT_OK; }
    else if (!a.empty() && a[0] == '-') { fprintf (stderr, "ERROR unknown option '%s'\n", a.c_str()); usage(); return EXIT_OTHER; }
    else pos.push_back (a);
  }
  if (pos.size() != 2) { usage(); return EXIT_OTHER; }
  opt.input = pos[0];
  opt.output = pos[1];

  // OCCT signal handling (no FPE trapping: feenableexcept is not available on musl)
  try { OSD::SetSignal (Standard_False); } catch (...) {}
  // keep OCCT quiet on stdout
  try
  {
    Handle(Message_Messenger) msgr = Message::DefaultMessenger();
    msgr->RemovePrinters (STANDARD_TYPE(Message_Printer));
    msgr->AddPrinter (new NullPrinter());
  }
  catch (...) {}

  int code = EXIT_OTHER;
  try
  {
    OCC_CATCH_SIGNALS
    code = run (opt);
  }
  catch (ConvError const& e)
  {
    fprintf (stderr, "ERROR %s\n", e.msg.c_str());
    fflush (stderr);
    code = e.code;
  }
  catch (Standard_Failure const& e)
  {
    fprintf (stderr, "ERROR OCCT exception: %s (%s)\n", e.GetMessageString() ? e.GetMessageString() : "", e.DynamicType()->Name());
    fflush (stderr);
    code = EXIT_PARSE;
  }
  catch (std::bad_alloc const&)
  {
    fprintf (stderr, "ERROR out of memory\n");
    fflush (stderr);
    code = EXIT_OTHER;
  }
  catch (std::exception const& e)
  {
    fprintf (stderr, "ERROR %s\n", e.what());
    fflush (stderr);
    code = EXIT_OTHER;
  }
  catch (...)
  {
    fprintf (stderr, "ERROR unknown exception\n");
    fflush (stderr);
    code = EXIT_OTHER;
  }
  if (code != EXIT_OK)
  {
    // do not leave a partial output file behind
    remove (opt.output.c_str());
  }
  fflush (stdout);
  // exit without running OCCT static destructors (avoids issues with the
  // XCAF application singleton on some platforms)
  _exit (code);
}
