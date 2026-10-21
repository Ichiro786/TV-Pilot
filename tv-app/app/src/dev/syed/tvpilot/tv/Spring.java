package dev.syed.tvpilot.tv;

/** A damped spring (the bar's motion): call step(dt) every frame. */
final class Spring {
    float value, velocity, target;
    final float stiffness, damping;

    Spring(float stiffness, float dampingRatio, float start) {
        this.stiffness = stiffness;
        this.damping = 2f * dampingRatio * (float) Math.sqrt(stiffness);
        value = target = start;
    }

    void step(float dt) {
        // Sub-steps keep stiff springs stable at 30-60 fps.
        int n = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / n;
        for (int i = 0; i < n; i++) {
            float a = -stiffness * (value - target) - damping * velocity;
            velocity += a * h;
            value += velocity * h;
        }
    }

    boolean resting() {
        return Math.abs(value - target) < 0.001f && Math.abs(velocity) < 0.01f;
    }

    void snap(float v) {
        value = target = v;
        velocity = 0;
    }
}
