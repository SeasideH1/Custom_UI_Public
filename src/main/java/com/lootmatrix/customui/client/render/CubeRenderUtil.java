package com.lootmatrix.customui.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

/**
 * Utility for rendering a full cube to depth buffer.
 */
public class CubeRenderUtil {

    /**
     * Writes a full 1x1x1 cube to the vertex consumer (only position data).
     */
    public static void writeFullCube(VertexConsumer vc, Matrix4f m) {
        float[][] v = {
                {0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0},
                {0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}
        };

        // Each face is a quad (4 vertices)
        int[][] faces = {
                {0, 1, 2, 3}, // back face (z=0)
                {5, 4, 7, 6}, // front face (z=1)
                {4, 0, 3, 7}, // left face (x=0)
                {1, 5, 6, 2}, // right face (x=1)
                {3, 2, 6, 7}, // top face (y=1)
                {4, 5, 1, 0}  // bottom face (y=0)
        };

        for (int[] f : faces) {
            for (int i : f) {
                vc.vertex(m, v[i][0], v[i][1], v[i][2]).endVertex();
            }
        }
    }

    /**
     * Writes an extended frustum-like depth volume.
     * The front face is the 1x1 block face, the back face extends FAR distance
     * in the opposite direction of the camera (away from viewer).
     *
     * @param vc   Vertex consumer
     * @param m    Transformation matrix
     * @param dirX Direction X from block to camera (normalized)
     * @param dirY Direction Y from block to camera (normalized)
     * @param dirZ Direction Z from block to camera (normalized)
     * @param far  Distance to extend the back face
     */
    public static void writeExtendedCube(VertexConsumer vc, Matrix4f m, float dirX, float dirY, float dirZ, float far) {
        // Front vertices (1x1x1 block)
        float[][] front = {
                {0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0},
                {0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}
        };

        // Back vertices extended away from camera
        // Direction from camera to block is opposite of (dirX, dirY, dirZ)
        float extX = -dirX * far;
        float extY = -dirY * far;
        float extZ = -dirZ * far;

        float[][] back = new float[8][3];
        for (int i = 0; i < 8; i++) {
            back[i][0] = front[i][0] + extX;
            back[i][1] = front[i][1] + extY;
            back[i][2] = front[i][2] + extZ;
        }

        // Render front face (facing camera)
        // We need to determine which faces are front-facing based on camera direction
        // For simplicity, render all 6 faces of the extended volume

        // Front cap (original block face closest to camera)
        // This depends on which axis the camera is primarily looking from
        // For a general solution, render all faces

        // Define the 6 faces using front and back vertices
        // Each face connects 4 front vertices to 4 back vertices appropriately

        // Face indices for a frustum/extended box:
        // The front face (cap) closest to camera
        // The back face (cap) far from camera
        // 4 side faces connecting front to back

        // Render front cap - we need the face facing the camera
        // Render all original block faces
        int[][] frontFaces = {
                {0, 1, 2, 3}, // -Z face
                {5, 4, 7, 6}, // +Z face
                {4, 0, 3, 7}, // -X face
                {1, 5, 6, 2}, // +X face
                {3, 2, 6, 7}, // +Y face
                {4, 5, 1, 0}  // -Y face
        };

        // Render front cap faces
        for (int[] f : frontFaces) {
            for (int i : f) {
                vc.vertex(m, front[i][0], front[i][1], front[i][2]).endVertex();
            }
        }

        // Render back cap faces (reversed winding for correct facing)
        int[][] backFaces = {
                {3, 2, 1, 0}, // -Z face reversed
                {6, 7, 4, 5}, // +Z face reversed
                {7, 3, 0, 4}, // -X face reversed
                {2, 6, 5, 1}, // +X face reversed
                {7, 6, 2, 3}, // +Y face reversed
                {0, 1, 5, 4}  // -Y face reversed
        };

        for (int[] f : backFaces) {
            for (int i : f) {
                vc.vertex(m, back[i][0], back[i][1], back[i][2]).endVertex();
            }
        }

        // Render side faces connecting front to back
        // Each edge of the cube connects to its extended counterpart
        int[][] sidePairs = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0}, // Z=0 face edges
                {4, 5}, {5, 6}, {6, 7}, {7, 4}, // Z=1 face edges
                {0, 4}, {1, 5}, {2, 6}, {3, 7}  // connecting edges
        };

        // Side quads - connect front edge to back edge
        // Edge 0-1 on front connects to edge 0-1 on back
        int[][] sideQuads = {
                {0, 1, 1, 0}, // front 0,1 -> back 1,0 (indices into front/back arrays)
                {1, 2, 2, 1},
                {2, 3, 3, 2},
                {3, 0, 0, 3},
                {4, 0, 0, 4},
                {5, 1, 1, 5},
                {6, 2, 2, 6},
                {7, 3, 3, 7},
                {4, 5, 5, 4},
                {5, 6, 6, 5},
                {6, 7, 7, 6},
                {7, 4, 4, 7}
        };

        // Actually, for proper side faces, we connect 4 vertices forming a quad
        // front[i], front[j], back[j], back[i] for each edge i-j

        // Define proper side quads
        // For each original face edge, create a quad connecting front to back
        renderSideQuad(vc, m, front, back, 0, 1);
        renderSideQuad(vc, m, front, back, 1, 2);
        renderSideQuad(vc, m, front, back, 2, 3);
        renderSideQuad(vc, m, front, back, 3, 0);
        renderSideQuad(vc, m, front, back, 4, 5);
        renderSideQuad(vc, m, front, back, 5, 6);
        renderSideQuad(vc, m, front, back, 6, 7);
        renderSideQuad(vc, m, front, back, 7, 4);
        renderSideQuad(vc, m, front, back, 0, 4);
        renderSideQuad(vc, m, front, back, 1, 5);
        renderSideQuad(vc, m, front, back, 2, 6);
        renderSideQuad(vc, m, front, back, 3, 7);
    }

    private static void renderSideQuad(VertexConsumer vc, Matrix4f m, float[][] front, float[][] back, int i, int j) {
        // Quad: front[i], front[j], back[j], back[i]
        vc.vertex(m, front[i][0], front[i][1], front[i][2]).endVertex();
        vc.vertex(m, front[j][0], front[j][1], front[j][2]).endVertex();
        vc.vertex(m, back[j][0], back[j][1], back[j][2]).endVertex();
        vc.vertex(m, back[i][0], back[i][1], back[i][2]).endVertex();
    }
}

