package com.jme3.scene.plugins.blender.meshes;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingVolume;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Mesh.Mode;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.VertexBuffer.Usage;
import com.jme3.scene.plugins.blender.BlenderContext;
import com.jme3.scene.plugins.blender.BlenderContext.LoadedDataType;
import com.jme3.scene.plugins.blender.file.BlenderFileException;
import com.jme3.scene.plugins.blender.file.Structure;
import com.jme3.scene.plugins.blender.materials.MaterialContext;
import com.jme3.scene.plugins.blender.meshes.IndexesLoop.IndexPredicate;
import com.jme3.scene.plugins.blender.meshes.MeshBuffers.BoneBuffersData;
import com.jme3.scene.plugins.blender.modifiers.Modifier;
import com.jme3.scene.plugins.blender.objects.Properties;

/**
 * The class extends Geometry so that it can be temporalily added to the object's node.
 * Later each such node's child will be transformed into a list of geometries.
 * 
 * @author Marcin Roguski (Kaelthas)
 */
public class TemporalMesh extends Geometry {
    private static final Logger        LOGGER                    = Logger.getLogger(TemporalMesh.class.getName());

    /** The blender context. */
    protected final BlenderContext     blenderContext;

    /** The mesh's structure. */
    protected final Structure          meshStructure;

    /** Loaded vertices. */
    protected List<Vector3f>           vertices                  = new ArrayList<Vector3f>();
    /** Loaded normals. */
    protected List<Vector3f>           normals                   = new ArrayList<Vector3f>();
    /** Loaded vertex groups. */
    protected List<Map<String, Float>> vertexGroups              = new ArrayList<Map<String, Float>>();
    /** Loaded vertex colors. */
    protected List<byte[]>             verticesColors            = new ArrayList<byte[]>();

    /** Materials used by the mesh. */
    protected MaterialContext[]        materials;
    /** The properties of the mesh. */
    protected Properties               properties;
    /** The bone indexes. */
    protected Map<String, Integer>     boneIndexes               = new HashMap<String, Integer>();
    /** The modifiers that should be applied after the mesh has been created. */
    protected List<Modifier>           postMeshCreationModifiers = new ArrayList<Modifier>();

    /** The faces of the mesh. */
    protected List<Face>               faces                     = new ArrayList<Face>();
    /** The edges of the mesh. */
    protected List<Edge>               edges                     = new ArrayList<Edge>();
    /** The points of the mesh. */
    protected List<Point>              points                    = new ArrayList<Point>();

    /** The bounding box of the temporal mesh. */
    protected BoundingBox              boundingBox;

    /**
     * Creates a temporal mesh based on the given mesh structure.
     * @param meshStructure
     *            the mesh structure
     * @param blenderContext
     *            the blender context
     * @throws BlenderFileException
     *             an exception is thrown when problems with file reading occur
     */
    public TemporalMesh(Structure meshStructure, BlenderContext blenderContext) throws BlenderFileException {
        this(meshStructure, blenderContext, true);
    }

    /**
     * Creates a temporal mesh based on the given mesh structure.
     * @param meshStructure
     *            the mesh structure
     * @param blenderContext
     *            the blender context
     * @param loadData
     *            tells if the data should be loaded from the mesh structure
     * @throws BlenderFileException
     *             an exception is thrown when problems with file reading occur
     */
    protected TemporalMesh(Structure meshStructure, BlenderContext blenderContext, boolean loadData) throws BlenderFileException {
        this.blenderContext = blenderContext;
        name = meshStructure.getName();
        this.meshStructure = meshStructure;

        if (loadData) {
            MeshHelper meshHelper = blenderContext.getHelper(MeshHelper.class);

            meshHelper.loadVerticesAndNormals(meshStructure, vertices, normals);
            verticesColors = meshHelper.loadVerticesColors(meshStructure, blenderContext);
            LinkedHashMap<String, List<Vector2f>> userUVGroups = meshHelper.loadUVCoordinates(meshStructure);
            vertexGroups = meshHelper.loadVerticesGroups(meshStructure);

            faces = Face.loadAll(meshStructure, userUVGroups, verticesColors, this, blenderContext);
            edges = Edge.loadAll(meshStructure);
            points = Point.loadAll(meshStructure);
        }
    }

    @Override
    public TemporalMesh clone() {
        try {
            TemporalMesh result = new TemporalMesh(meshStructure, blenderContext, false);
            for (Vector3f v : vertices) {
                result.vertices.add(v.clone());
            }
            for (Vector3f n : normals) {
                result.normals.add(n.clone());
            }
            for (Map<String, Float> group : vertexGroups) {
                result.vertexGroups.add(new HashMap<String, Float>(group));
            }
            for (byte[] vertColor : verticesColors) {
                result.verticesColors.add(vertColor.clone());
            }
            result.materials = materials;
            result.properties = properties;
            result.boneIndexes.putAll(boneIndexes);
            result.postMeshCreationModifiers.addAll(postMeshCreationModifiers);
            for (Face face : faces) {
                result.faces.add(face.clone());
            }
            for (Edge edge : edges) {
                result.edges.add(edge.clone());
            }
            for (Point point : points) {
                result.points.add(point.clone());
            }
            return result;
        } catch (BlenderFileException e) {
            LOGGER.log(Level.SEVERE, "Error while cloning the temporal mesh: {0}. Returning null.", e.getLocalizedMessage());
        }
        return null;
    }

    /**
     * @return the vertices of the mesh
     */
    protected List<Vector3f> getVertices() {
        return vertices;
    }

    /**
     * @return the normals of the mesh
     */
    protected List<Vector3f> getNormals() {
        return normals;
    }

    @Override
    public void updateModelBound() {
        if (boundingBox == null) {
            boundingBox = new BoundingBox();
        }
        Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        Vector3f max = new Vector3f(Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE);
        for (Vector3f v : vertices) {
            min.set(Math.min(min.x, v.x), Math.min(min.y, v.y), Math.min(min.z, v.z));
            max.set(Math.max(max.x, v.x), Math.max(max.y, v.y), Math.max(max.z, v.z));
        }
        boundingBox.setMinMax(min, max);
    }

    @Override
    public BoundingVolume getModelBound() {
        this.updateModelBound();
        return boundingBox;
    }

    @Override
    public BoundingVolume getWorldBound() {
        this.updateModelBound();
        Node parent = this.getParent();
        if (parent != null) {
            BoundingVolume bv = boundingBox.clone();
            bv.setCenter(parent.getWorldTranslation());
            return bv;
        } else {
            return boundingBox;
        }
    }

    /**
     * Triangulates the mesh.
     */
    public void triangulate() {
        LOGGER.fine("Triangulating temporal mesh.");
        for (Face face : faces) {
            face.triangulate(vertices, normals);
        }
    }

    /**
     * The method appends the given mesh to the current one. New faces and vertices and indexes are added.
     * @param mesh
     *            the mesh to be appended
     */
    public void append(TemporalMesh mesh) {
        // we need to shift the indexes in faces, lines and points
        int shift = vertices.size();
        if (shift > 0) {
            for (Face face : mesh.faces) {
                face.getIndexes().shiftIndexes(shift, null);
                face.setTemporalMesh(this);
            }
            for (Edge edge : mesh.edges) {
                edge.shiftIndexes(shift, null);
            }
            for (Point point : mesh.points) {
                point.shiftIndexes(shift, null);
            }
        }

        faces.addAll(mesh.faces);
        edges.addAll(mesh.edges);
        points.addAll(mesh.points);

        vertices.addAll(mesh.vertices);
        normals.addAll(mesh.normals);
        vertexGroups.addAll(mesh.vertexGroups);
        verticesColors.addAll(mesh.verticesColors);
        boneIndexes.putAll(mesh.boneIndexes);
    }

    /**
     * Translate all vertices by the given vector.
     * @param translation
     *            the translation vector
     * @return this mesh after translation (NO new instance is created)
     */
    public TemporalMesh translate(Vector3f translation) {
        for (Vector3f v : vertices) {
            v.addLocal(translation);
        }
        return this;
    }

    /**
     * Sets the properties of the mesh.
     * @param properties
     *            the properties of the mesh
     */
    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    /**
     * Sets the materials of the mesh.
     * @param materials
     *            the materials of the mesh
     */
    public void setMaterials(MaterialContext[] materials) {
        this.materials = materials;
    }

    /**
     * Adds bone index to the mesh.
     * @param boneName
     *            the name of the bone
     * @param boneIndex
     *            the index of the bone
     */
    public void addBoneIndex(String boneName, Integer boneIndex) {
        boneIndexes.put(boneName, boneIndex);
    }

    /**
     * The modifier to be applied after the geometries are created.
     * @param modifier
     *            the modifier to be applied
     */
    public void applyAfterMeshCreate(Modifier modifier) {
        postMeshCreationModifiers.add(modifier);
    }

    @Override
    public int getVertexCount() {
        return vertices.size();
    }

    /**
     * Returns the vertex at the given position.
     * @param i
     *            the vertex position
     * @return the vertex at the given position
     */
    public Vector3f getVertex(int i) {
        return vertices.get(i);
    }

    /**
     * Returns the normal at the given position.
     * @param i
     *            the normal position
     * @return the normal at the given position
     */
    public Vector3f getNormal(int i) {
        return normals.get(i);
    }

    /**
     * Returns the vertex groups at the given vertex index.
     * @param i
     *            the vertex groups for vertex with a given index
     * @return the vertex groups at the given vertex index
     */
    public Map<String, Float> getVertexGroups(int i) {
        return vertexGroups.size() > i ? vertexGroups.get(i) : null;
    }

    /**
     * @return a collection of vertex group names for this mesh
     */
    public Collection<String> getVertexGroupNames() {
        Set<String> result = new HashSet<String>();
        for (Map<String, Float> groups : vertexGroups) {
            result.addAll(groups.keySet());
        }
        return result;
    }

    /**
     * Removes all vertices from the mesh.
     */
    public void clear() {
        vertices.clear();
        normals.clear();
        vertexGroups.clear();
        verticesColors.clear();
        faces.clear();
        edges.clear();
        points.clear();
    }

    /**
     * Every face, edge and point that contains
     * the vertex will be removed.
     * @param index
     *            the index of a vertex to be removed
     * @throws IndexOutOfBoundsException
     *             thrown when given index is negative or beyond the count of vertices
     */
    public void removeVertexAt(final int index) {
        if (index < 0 || index >= vertices.size()) {
            throw new IndexOutOfBoundsException("The given index is out of bounds: " + index);
        }

        vertices.remove(index);
        normals.remove(index);
        if(vertexGroups.size() > 0) {
            vertexGroups.remove(index);
        }
        if(verticesColors.size() > 0) {
            verticesColors.remove(index);
        }

        IndexPredicate shiftPredicate = new IndexPredicate() {
            @Override
            public boolean execute(Integer i) {
                return i > index;
            }
        };
        for (int i = faces.size() - 1; i >= 0; --i) {
            Face face = faces.get(i);
            if (face.getIndexes().indexOf(index) >= 0) {
                faces.remove(i);
            } else {
                face.getIndexes().shiftIndexes(-1, shiftPredicate);
            }
        }
        for (int i = edges.size() - 1; i >= 0; --i) {
            Edge edge = edges.get(i);
            if (edge.getFirstIndex() == index || edge.getSecondIndex() == index) {
                edges.remove(i);
            } else {
                edge.shiftIndexes(-1, shiftPredicate);
            }
        }
        for (int i = points.size() - 1; i >= 0; --i) {
            Point point = points.get(i);
            if (point.getIndex() == index) {
                points.remove(i);
            } else {
                point.shiftIndexes(-1, shiftPredicate);
            }
        }
    }

    /**
     * Flips the order of the mesh's indexes.
     */
    public void flipIndexes() {
        for (Face face : faces) {
            face.flipIndexes();
        }
        for (Edge edge : edges) {
            edge.flipIndexes();
        }
        Collections.reverse(points);
    }

    /**
     * Flips UV coordinates.
     * @param u
     *            indicates if U coords should be flipped
     * @param v
     *            indicates if V coords should be flipped
     */
    public void flipUV(boolean u, boolean v) {
        for (Face face : faces) {
            face.flipUV(u, v);
        }
    }

    /**
     * The mesh builds geometries from the mesh. The result is stored in the blender context
     * under the mesh's OMA.
     */
    public void toGeometries() {
        LOGGER.log(Level.FINE, "Converting temporal mesh {0} to jme geometries.", name);
        List<Geometry> result = new ArrayList<Geometry>();
        MeshHelper meshHelper = blenderContext.getHelper(MeshHelper.class);
        Node parent = this.getParent();
        parent.detachChild(this);

        this.prepareFacesGeometry(result, meshHelper);
        this.prepareLinesGeometry(result, meshHelper);
        this.preparePointsGeometry(result, meshHelper);

        blenderContext.addLoadedFeatures(meshStructure.getOldMemoryAddress(), LoadedDataType.FEATURE, result);

        for (Geometry geometry : result) {
            parent.attachChild(geometry);
        }

        for (Modifier modifier : postMeshCreationModifiers) {
            modifier.postMeshCreationApply(parent, blenderContext);
        }
    }

    /**
     * The method creates geometries from faces.
     * @param result
     *            the list where new geometries will be appended
     * @param meshHelper
     *            the mesh helper
     */
    protected void prepareFacesGeometry(List<Geometry> result, MeshHelper meshHelper) {
        LOGGER.fine("Preparing faces geometries.");
        this.triangulate();

        Vector3f[] tempVerts = new Vector3f[3];
        Vector3f[] tempNormals = new Vector3f[3];
        byte[][] tempVertColors = new byte[3][];
        List<Map<Float, Integer>> boneBuffers = new ArrayList<Map<Float, Integer>>(3);

        LOGGER.log(Level.FINE, "Appending {0} faces to mesh buffers.", faces.size());
        Map<Integer, MeshBuffers> faceMeshes = new HashMap<Integer, MeshBuffers>();
        for (Face face : faces) {
            MeshBuffers meshBuffers = faceMeshes.get(face.getMaterialNumber());
            if (meshBuffers == null) {
                meshBuffers = new MeshBuffers(face.getMaterialNumber());
                faceMeshes.put(face.getMaterialNumber(), meshBuffers);
            }

            List<List<Integer>> triangulatedIndexes = face.getCurrentIndexes();
            List<byte[]> vertexColors = face.getVertexColors();

            for (List<Integer> indexes : triangulatedIndexes) {
                assert indexes.size() == 3 : "The mesh has not been properly triangulated!";
                boneBuffers.clear();
                for (int i = 0; i < 3; ++i) {
                    int vertIndex = indexes.get(i);
                    tempVerts[i] = vertices.get(vertIndex);
                    tempNormals[i] = normals.get(vertIndex);
                    tempVertColors[i] = vertexColors != null ? vertexColors.get(i) : null;

                    if (boneIndexes.size() > 0) {
                        Map<Float, Integer> boneBuffersForVertex = new HashMap<Float, Integer>();
                        Map<String, Float> vertexGroupsForVertex = vertexGroups.get(vertIndex);
                        for (Entry<String, Integer> entry : boneIndexes.entrySet()) {
                            if (vertexGroupsForVertex.containsKey(entry.getKey())) {
                                boneBuffersForVertex.put(vertexGroupsForVertex.get(entry.getKey()), entry.getValue());
                            }
                        }
                        boneBuffers.add(boneBuffersForVertex);
                    }
                }

                meshBuffers.append(face.isSmooth(), tempVerts, tempNormals, face.getUvSets(), tempVertColors, boneBuffers);
            }
        }

        LOGGER.fine("Converting mesh buffers to geometries.");
        Map<Geometry, MeshBuffers> geometryToBuffersMap = new HashMap<Geometry, MeshBuffers>();
        for (Entry<Integer, MeshBuffers> entry : faceMeshes.entrySet()) {
            MeshBuffers meshBuffers = entry.getValue();

            Mesh mesh = new Mesh();

            if (meshBuffers.isShortIndexBuffer()) {
                mesh.setBuffer(Type.Index, 1, (ShortBuffer) meshBuffers.getIndexBuffer());
            } else {
                mesh.setBuffer(Type.Index, 1, (IntBuffer) meshBuffers.getIndexBuffer());
            }
            mesh.setBuffer(meshBuffers.getPositionsBuffer());
            mesh.setBuffer(meshBuffers.getNormalsBuffer());
            if (meshBuffers.areVertexColorsUsed()) {
                mesh.setBuffer(Type.Color, 4, meshBuffers.getVertexColorsBuffer());
                mesh.getBuffer(Type.Color).setNormalized(true);
            }

            BoneBuffersData boneBuffersData = meshBuffers.getBoneBuffers();
            if (boneBuffersData != null) {
                mesh.setMaxNumWeights(boneBuffersData.maximumWeightsPerVertex);
                mesh.setBuffer(boneBuffersData.verticesWeights);
                mesh.setBuffer(boneBuffersData.verticesWeightsIndices);

                LOGGER.fine("Generating bind pose and normal buffers.");
                mesh.generateBindPose(true);

                // change the usage type of vertex and normal buffers from Static to Stream
                mesh.getBuffer(Type.Position).setUsage(Usage.Stream);
                mesh.getBuffer(Type.Normal).setUsage(Usage.Stream);

                // creating empty buffers for HW skinning; the buffers will be setup if ever used
                VertexBuffer verticesWeightsHW = new VertexBuffer(Type.HWBoneWeight);
                VertexBuffer verticesWeightsIndicesHW = new VertexBuffer(Type.HWBoneIndex);
                mesh.setBuffer(verticesWeightsHW);
                mesh.setBuffer(verticesWeightsIndicesHW);
            }

            Geometry geometry = new Geometry(name + (result.size() + 1), mesh);
            if (properties != null && properties.getValue() != null) {
                meshHelper.applyProperties(geometry, properties);
            }
            result.add(geometry);

            geometryToBuffersMap.put(geometry, meshBuffers);
        }

        LOGGER.fine("Applying materials to geometries.");
        for (Entry<Geometry, MeshBuffers> entry : geometryToBuffersMap.entrySet()) {
            int materialIndex = entry.getValue().getMaterialIndex();
            Geometry geometry = entry.getKey();
            if (materialIndex >= 0 && materials != null && materials.length > materialIndex && materials[materialIndex] != null) {
                materials[materialIndex].applyMaterial(geometry, meshStructure.getOldMemoryAddress(), entry.getValue().getUvCoords(), blenderContext);
            } else {
                geometry.setMaterial(blenderContext.getDefaultMaterial());
            }
        }
    }

    /**
     * The method creates geometries from lines.
     * @param result
     *            the list where new geometries will be appended
     * @param meshHelper
     *            the mesh helper
     */
    protected void prepareLinesGeometry(List<Geometry> result, MeshHelper meshHelper) {
        if (edges.size() > 0) {
            LOGGER.fine("Preparing lines geometries.");

            List<List<Integer>> separateEdges = new ArrayList<List<Integer>>();
            List<Edge> edges = new ArrayList<Edge>(this.edges);
            while (edges.size() > 0) {
                boolean edgeAppended = false;
                int edgeIndex = 0;
                for (List<Integer> list : separateEdges) {
                    for (edgeIndex = 0; edgeIndex < edges.size() && !edgeAppended; ++edgeIndex) {
                        Edge edge = edges.get(edgeIndex);
                        if (list.get(0).equals(edge.getFirstIndex())) {
                            list.add(0, edge.getSecondIndex());
                            --edgeIndex;
                            edgeAppended = true;
                        } else if (list.get(0).equals(edge.getSecondIndex())) {
                            list.add(0, edge.getFirstIndex());
                            --edgeIndex;
                            edgeAppended = true;
                        } else if (list.get(list.size() - 1).equals(edge.getFirstIndex())) {
                            list.add(edge.getSecondIndex());
                            --edgeIndex;
                            edgeAppended = true;
                        } else if (list.get(list.size() - 1).equals(edge.getSecondIndex())) {
                            list.add(edge.getFirstIndex());
                            --edgeIndex;
                            edgeAppended = true;
                        }
                    }
                    if (edgeAppended) {
                        break;
                    }
                }
                Edge edge = edges.remove(edgeAppended ? edgeIndex : 0);
                if (!edgeAppended) {
                    separateEdges.add(new ArrayList<Integer>(Arrays.asList(edge.getFirstIndex(), edge.getSecondIndex())));
                }
            }

            for (List<Integer> list : separateEdges) {
                MeshBuffers meshBuffers = new MeshBuffers(0);
                for (int index : list) {
                    meshBuffers.append(vertices.get(index), normals.get(index));
                }
                Mesh mesh = new Mesh();
                mesh.setPointSize(2);
                mesh.setMode(Mode.LineStrip);
                if (meshBuffers.isShortIndexBuffer()) {
                    mesh.setBuffer(Type.Index, 1, (ShortBuffer) meshBuffers.getIndexBuffer());
                } else {
                    mesh.setBuffer(Type.Index, 1, (IntBuffer) meshBuffers.getIndexBuffer());
                }
                mesh.setBuffer(meshBuffers.getPositionsBuffer());
                mesh.setBuffer(meshBuffers.getNormalsBuffer());

                Geometry geometry = new Geometry(meshStructure.getName() + (result.size() + 1), mesh);
                geometry.setMaterial(meshHelper.getBlackUnshadedMaterial(blenderContext));
                if (properties != null && properties.getValue() != null) {
                    meshHelper.applyProperties(geometry, properties);
                }
                result.add(geometry);
            }
        }
    }

    /**
     * The method creates geometries from points.
     * @param result
     *            the list where new geometries will be appended
     * @param meshHelper
     *            the mesh helper
     */
    protected void preparePointsGeometry(List<Geometry> result, MeshHelper meshHelper) {
        if (points.size() > 0) {
            LOGGER.fine("Preparing point geometries.");

            MeshBuffers pointBuffers = new MeshBuffers(0);
            for (Point point : points) {
                pointBuffers.append(vertices.get(point.getIndex()), normals.get(point.getIndex()));
            }
            Mesh pointsMesh = new Mesh();
            pointsMesh.setMode(Mode.Points);
            pointsMesh.setPointSize(blenderContext.getBlenderKey().getPointsSize());
            if (pointBuffers.isShortIndexBuffer()) {
                pointsMesh.setBuffer(Type.Index, 1, (ShortBuffer) pointBuffers.getIndexBuffer());
            } else {
                pointsMesh.setBuffer(Type.Index, 1, (IntBuffer) pointBuffers.getIndexBuffer());
            }
            pointsMesh.setBuffer(pointBuffers.getPositionsBuffer());
            pointsMesh.setBuffer(pointBuffers.getNormalsBuffer());

            Geometry pointsGeometry = new Geometry(meshStructure.getName() + (result.size() + 1), pointsMesh);
            pointsGeometry.setMaterial(meshHelper.getBlackUnshadedMaterial(blenderContext));
            if (properties != null && properties.getValue() != null) {
                meshHelper.applyProperties(pointsGeometry, properties);
            }
            result.add(pointsGeometry);
        }
    }

    @Override
    public String toString() {
        return "TemporalMesh [name=" + name + ", vertices.size()=" + vertices.size() + "]";
    }
}
