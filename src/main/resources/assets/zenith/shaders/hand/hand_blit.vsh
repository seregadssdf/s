#version 330

// Полноэкранный квад композита руки. Вершины уже приходят в NDC (-1..1),
// поэтому матрицы не применяем: ModelViewMat во время renderWorld — мировая,
// она бы увезла квад за экран.

in vec3 Position;

out vec2 handUv;

void main() {
    gl_Position = vec4(Position, 1.0);
    handUv = (Position.xy + 1.0) / 2.0;
}
