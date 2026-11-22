package com.newtube.mobile.ui.common;

/**
 * GUTUBE(jelly-radio): Analytical closed-form mass-spring solver replicating the exact
 * motion physics of Framer Motion / Motion: spring(k, m, bounce).
 *
 * <p>Specification & Equations:
 * <pre>
 *   stiffness = k
 *   mass = m
 *   damping = 2 * sqrt(k * m) * (1 - bounce)
 *   zeta = 1 - bounce
 *   omega0 = sqrt(k / m)
 *   omegaD = omega0 * sqrt(1 - zeta^2)
 * </pre>
 *
 * <p>Evaluating closed-form analytical solutions at time t provides:
 * <ul>
 *   <li>Exact zero-drift trajectory without numerical step approximations</li>
 *   <li>Instant O(1) evaluation per frame</li>
 *   <li>Seamless retargeting mid-animation with velocity continuity (C^1)</li>
 * </ul>
 */
public final class JellySpringPhysics {

    private JellySpringPhysics() {
    }

    public static final class SpringConfig {
        public final float k; // stiffness
        public final float m; // mass
        public final float bounce;
        public final float zeta; // damping ratio: 1 - bounce
        public final float omega0; // natural frequency
        public final float omegaD; // damped angular frequency

        public SpringConfig(float k, float m, float bounce) {
            this.k = Math.max(1f, k);
            this.m = Math.max(0.01f, m);
            this.bounce = Math.max(0f, Math.min(0.99f, bounce));
            this.zeta = Math.max(0.01f, 1f - this.bounce);
            this.omega0 = (float) Math.sqrt(this.k / this.m);
            float under = 1f - this.zeta * this.zeta;
            this.omegaD = under > 0f ? (float) (this.omega0 * Math.sqrt(under)) : 0.0001f;
        }
    }

    /**
     * Evaluates underdamped or critically damped spring displacement at time t (seconds).
     *
     * @param cfg Spring configuration
     * @param x0 Initial position
     * @param v0 Initial velocity (units / second)
     * @param x1 Target position
     * @param tSec Elapsed time in seconds
     * @return Position at time tSec
     */
    public static float eval(SpringConfig cfg, float x0, float v0, float x1, float tSec) {
        if (tSec <= 0f) {
            return x0;
        }
        float y0 = x0 - x1;
        if (Math.abs(y0) < 0.0001f && Math.abs(v0) < 0.0001f) {
            return x1;
        }
        if (cfg.zeta < 1f) {
            // Underdamped
            float decay = (float) Math.exp(-cfg.zeta * cfg.omega0 * tSec);
            float cos = (float) Math.cos(cfg.omegaD * tSec);
            float sin = (float) Math.sin(cfg.omegaD * tSec);
            float a = y0;
            float b = (v0 + cfg.zeta * cfg.omega0 * y0) / cfg.omegaD;
            return x1 + decay * (a * cos + b * sin);
        } else {
            // Critically damped / Overdamped
            float decay = (float) Math.exp(-cfg.omega0 * tSec);
            float a = y0;
            float b = v0 + cfg.omega0 * y0;
            return x1 + decay * (a + b * tSec);
        }
    }

    /**
     * Evaluates velocity at time t (seconds).
     */
    public static float evalVelocity(SpringConfig cfg, float x0, float v0, float x1, float tSec) {
        if (tSec <= 0f) {
            return v0;
        }
        float y0 = x0 - x1;
        if (cfg.zeta < 1f) {
            float decay = (float) Math.exp(-cfg.zeta * cfg.omega0 * tSec);
            float cos = (float) Math.cos(cfg.omegaD * tSec);
            float sin = (float) Math.sin(cfg.omegaD * tSec);
            float a = y0;
            float b = (v0 + cfg.zeta * cfg.omega0 * y0) / cfg.omegaD;
            return decay * ((b * cfg.omegaD - a * cfg.zeta * cfg.omega0) * cos
                    - (a * cfg.omegaD + b * cfg.zeta * cfg.omega0) * sin);
        } else {
            float decay = (float) Math.exp(-cfg.omega0 * tSec);
            float a = y0;
            float b = v0 + cfg.omega0 * y0;
            return decay * (b - cfg.omega0 * (a + b * tSec));
        }
    }
}
