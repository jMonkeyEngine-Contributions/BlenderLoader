package com.jme3.scene.plugins.blender.meshes.builders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Mesh;
import com.jme3.scene.plugins.blender.BlenderContext;
import com.jme3.scene.plugins.blender.file.BlenderFileException;
import com.jme3.scene.plugins.blender.file.DynamicArray;
import com.jme3.scene.plugins.blender.file.Pointer;
import com.jme3.scene.plugins.blender.file.Structure;
import com.jme3.scene.plugins.blender.materials.MaterialContext;

public class MeshBuilder {
    private boolean          fixUpAxis;
    private PointMeshBuilder pointMeshBuilder;
    private LineMeshBuilder  lineMeshBuilder;
    private FaceMeshBuilder  faceMeshBuilder;
    /**
     * This map's key is the vertex index from 'vertices 'table and the value are indices from 'vertexList'
     * positions (it simply tells which vertex is referenced where in the result list).
     */
    private Map<Integer, Map<Integer, List<Integer>>> globalVertexReferenceMap = new HashMap<Integer, Map<Integer,List<Integer>>>();
    
    public MeshBuilder(Structure meshStructure, MaterialContext[] materials, BlenderContext blenderContext) throws BlenderFileException {
        fixUpAxis = blenderContext.getBlenderKey().isFixUpAxis();
        Vector3f[][] verticesAndNormals = this.getVerticesAndNormals(meshStructure);

        faceMeshBuilder = new FaceMeshBuilder(verticesAndNormals, this.areGeneratedTexturesPresent(materials));
        faceMeshBuilder.readMesh(meshStructure, blenderContext);
        lineMeshBuilder = new LineMeshBuilder(verticesAndNormals);
        lineMeshBuilder.readMesh(meshStructure);
        pointMeshBuilder = new PointMeshBuilder(verticesAndNormals);
        pointMeshBuilder.readMesh(meshStructure);
    }

    public Map<Integer, List<Mesh>> buildMeshes() {
        Map<Integer, List<Mesh>> result = new HashMap<Integer, List<Mesh>>();

        Map<Integer, Mesh> meshes = faceMeshBuilder.buildMeshes();
        for (Entry<Integer, Mesh> entry : meshes.entrySet()) {
            List<Mesh> meshList = new ArrayList<Mesh>();
            meshList.add(entry.getValue());
            result.put(entry.getKey(), meshList);
        }

        meshes = lineMeshBuilder.buildMeshes();
        for (Entry<Integer, Mesh> entry : meshes.entrySet()) {
            List<Mesh> meshList = result.get(entry.getKey());
            if (meshList == null) {
                meshList = new ArrayList<Mesh>();
                result.put(entry.getKey(), meshList);
            }
            meshList.add(entry.getValue());
        }

        meshes = pointMeshBuilder.buildMeshes();
        for (Entry<Integer, Mesh> entry : meshes.entrySet()) {
            List<Mesh> meshList = result.get(entry.getKey());
            if (meshList == null) {
                meshList = new ArrayList<Mesh>();
                result.put(entry.getKey(), meshList);
            }
            meshList.add(entry.getValue());
        }

        globalVertexReferenceMap.putAll(faceMeshBuilder.getVertexReferenceMap());
        globalVertexReferenceMap.put(-1, lineMeshBuilder.getGlobalVertexReferenceMap());
        globalVertexReferenceMap.get(-1).putAll(pointMeshBuilder.getGlobalVertexReferenceMap());
        return result;
    }

    /**
     * @return <b>true</b> if the mesh has no vertices and <b>false</b> otherwise
     */
    public boolean isEmpty() {
        return faceMeshBuilder.isEmpty() && lineMeshBuilder.isEmpty() && pointMeshBuilder.isEmpty();
    }

    /**
     * @return a map that maps vertex index from reference array to its indices in the result list
     */
    public Map<Integer, Map<Integer, List<Integer>>> getVertexReferenceMap() {
        return globalVertexReferenceMap;
    }

    /**
     * @param materialNumber
     *            the material number that is appied to the mesh
     * @return UV coordinates of vertices that belong to the required mesh part
     */
    public LinkedHashMap<String, List<Vector2f>> getUVCoordinates(int materialNumber) {
        return faceMeshBuilder.getUVCoordinates(materialNumber);
    }

    /**
     * @return indicates if the mesh has UV coordinates
     */
    public boolean hasUVCoordinates() {
        return faceMeshBuilder.hasUVCoordinates();
    }

    /**
     * This method returns the vertices.
     * 
     * @param meshStructure
     *            the structure containing the mesh data
     * @return a list of two - element arrays, the first element is the vertex and the second - its normal
     * @throws BlenderFileException
     *             this exception is thrown when the blend file structure is somehow invalid or corrupted
     */
    @SuppressWarnings("unchecked")
    private Vector3f[][] getVerticesAndNormals(Structure meshStructure) throws BlenderFileException {
        int count = ((Number) meshStructure.getFieldValue("totvert")).intValue();
        Vector3f[][] result = new Vector3f[count][2];
        if (count == 0) {
            return result;
        }

        Pointer pMVert = (Pointer) meshStructure.getFieldValue("mvert");
        List<Structure> mVerts = pMVert.fetchData();
        if (fixUpAxis) {
            for (int i = 0; i < count; ++i) {
                DynamicArray<Number> coordinates = (DynamicArray<Number>) mVerts.get(i).getFieldValue("co");
                result[i][0] = new Vector3f(coordinates.get(0).floatValue(), coordinates.get(2).floatValue(), -coordinates.get(1).floatValue());

                DynamicArray<Number> normals = (DynamicArray<Number>) mVerts.get(i).getFieldValue("no");
                result[i][1] = new Vector3f(normals.get(0).shortValue() / 32767.0f, normals.get(2).shortValue() / 32767.0f, -normals.get(1).shortValue() / 32767.0f);
            }
        } else {
            for (int i = 0; i < count; ++i) {
                DynamicArray<Number> coordinates = (DynamicArray<Number>) mVerts.get(i).getFieldValue("co");
                result[i][0] = new Vector3f(coordinates.get(0).floatValue(), coordinates.get(1).floatValue(), coordinates.get(2).floatValue());

                DynamicArray<Number> normals = (DynamicArray<Number>) mVerts.get(i).getFieldValue("no");
                result[i][1] = new Vector3f(normals.get(0).shortValue() / 32767.0f, normals.get(1).shortValue() / 32767.0f, normals.get(2).shortValue() / 32767.0f);
            }
        }
        return result;
    }

    /**
     * @return <b>true</b> if the material has at least one generated component and <b>false</b> otherwise
     */
    private boolean areGeneratedTexturesPresent(MaterialContext[] materials) {
        if (materials != null) {
            for (MaterialContext material : materials) {
                if (material != null && material.hasGeneratedTextures()) {
                    return true;
                }
            }
        }
        return false;
    }
}
