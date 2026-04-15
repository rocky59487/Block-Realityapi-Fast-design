import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * Stage 3: Shaderc GLSL → SPIR-V コンパイル
 * Block Reality の compute shader がコンパイルできるか検証
 */
public class Stage3_Shaderc {

    static final String PASS = "  [PASS] ";
    static final String FAIL = "  [FAIL] ";
    static final String INFO = "  [INFO] ";

    // 最小 compute shader (PFSFのrbgs_smooth.comp.glslを模倣)
    static final String COMPUTE_SHADER_SRC = """
        #version 450
        layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

        layout(std430, binding = 0) buffer PhiBuffer    { float phi[];    };
        layout(std430, binding = 1) buffer SrcBuffer    { float source[]; };
        layout(std430, binding = 2) buffer ConductBuffer{ float cond[];   };

        layout(push_constant) uniform PushConst {
            int Nx; int Ny; int Nz;
            int colorPhase;
        };

        void main() {
            ivec3 gid = ivec3(gl_GlobalInvocationID);
            if (gid.x >= Nx || gid.y >= Ny || gid.z >= Nz) return;

            int idx = gid.z * Ny * Nx + gid.y * Nx + gid.x;

            // 6-connected Laplacian (simplified)
            float c = cond[idx];
            if (c <= 0.0) return;

            float sum = 0.0;
            float wsum = 0.0;

            // x neighbors
            if (gid.x > 0)    { float w = c; sum += phi[idx - 1] * w; wsum += w; }
            if (gid.x < Nx-1) { float w = c; sum += phi[idx + 1] * w; wsum += w; }
            // y neighbors
            if (gid.y > 0)    { float w = c; sum += phi[idx - Nx] * w; wsum += w; }
            if (gid.y < Ny-1) { float w = c; sum += phi[idx + Nx] * w; wsum += w; }

            if (wsum > 0.0) {
                phi[idx] = (source[idx] + sum) / wsum;
            }
        }
        """;

    // Fluid Jacobi shader (FluidGPUEngineのshaderを模倣)
    static final String FLUID_SHADER_SRC = """
        #version 450
        layout(local_size_x = 4, local_size_y = 4, local_size_z = 4) in;

        layout(std430, binding = 0) buffer VxBuf { float vx[]; };
        layout(std430, binding = 1) buffer VyBuf { float vy[]; };
        layout(std430, binding = 2) buffer VzBuf { float vz[]; };

        layout(push_constant) uniform PC {
            int Lx; int Ly; int Lz;
            float dt;
        };

        void main() {
            ivec3 g = ivec3(gl_GlobalInvocationID);
            if (g.x >= Lx || g.y >= Ly || g.z >= Lz) return;

            int idx = g.z * Ly * Lx + g.y * Lx + g.x;

            // Simple advection step
            float u = vx[idx];
            float v = vy[idx];
            float w = vz[idx];

            // Damping
            vx[idx] = u * 0.99;
            vy[idx] = v * 0.99;
            vz[idx] = w * 0.99;
        }
        """;

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Stage 3: Shaderc GLSL → SPIR-V コンパイル           ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        System.setProperty("org.lwjgl.librarypath", "/tmp/vk_smoke_test/natives");

        boolean ok = run();
        System.out.println();
        System.out.println(ok ? "Stage 3: PASSED" : "Stage 3: FAILED");
        System.exit(ok ? 0 : 1);
    }

    static boolean run() {
        // ─── 3a: shaderc コンパイラ作成 ───
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0) {
            System.out.println(FAIL + "shaderc_compiler_initialize failed (returned 0)");
            return false;
        }
        System.out.println(PASS + "shaderc_compiler_initialize OK: 0x" + Long.toHexString(compiler));

        // ─── 3b: compile options ───
        long options = Shaderc.shaderc_compile_options_initialize();
        if (options == 0) {
            System.out.println(FAIL + "shaderc_compile_options_initialize failed");
            Shaderc.shaderc_compiler_release(compiler);
            return false;
        }
        Shaderc.shaderc_compile_options_set_target_env(
            options,
            Shaderc.shaderc_target_env_vulkan,
            Shaderc.shaderc_env_version_vulkan_1_2
        );
        Shaderc.shaderc_compile_options_set_optimization_level(
            options,
            Shaderc.shaderc_optimization_level_performance
        );
        System.out.println(PASS + "Compile options: target=Vulkan 1.2, opt=performance");

        boolean allOk = true;

        // ─── 3c: PFSF compute shader コンパイル ───
        System.out.println(INFO + "Compiling PFSF compute shader...");
        allOk &= compileShader(compiler, options,
            "pfsf_rbgs.comp", COMPUTE_SHADER_SRC,
            Shaderc.shaderc_compute_shader);

        // ─── 3d: Fluid compute shader コンパイル ───
        System.out.println(INFO + "Compiling Fluid compute shader...");
        allOk &= compileShader(compiler, options,
            "fluid_advect.comp", FLUID_SHADER_SRC,
            Shaderc.shaderc_compute_shader);

        // Cleanup
        Shaderc.shaderc_compile_options_release(options);
        Shaderc.shaderc_compiler_release(compiler);
        System.out.println(PASS + "Shaderc compiler released cleanly");

        return allOk;
    }

    static boolean compileShader(long compiler, long options, String name, String src, int kind) {
        // VulkanComputeContext.compileGLSL() と同じパターン: CharSequence オーバーロード使用
        // ByteBuffer(true) → remaining() が null バイトを含み GLSL パースエラーになるため不可
        long result = Shaderc.shaderc_compile_into_spv(compiler, src, kind, name, "main", options);

        if (result == 0) {
            System.out.println(FAIL + name + ": shaderc_compile_into_spv returned null");
            return false;
        }

        int status    = Shaderc.shaderc_result_get_compilation_status(result);
        long wordCount = Shaderc.shaderc_result_get_length(result);
        long warnings  = Shaderc.shaderc_result_get_num_warnings(result);

        boolean ok = (status == Shaderc.shaderc_compilation_status_success);

        if (ok) {
            System.out.println(PASS + name + ": compiled OK — "
                + wordCount / 4 + " words SPIR-V, "
                + warnings + " warnings");
        } else {
            String errMsg = Shaderc.shaderc_result_get_error_message(result);
            System.out.println(FAIL + name + ": compilation FAILED\n    " + errMsg);
        }

        Shaderc.shaderc_result_release(result);
        return ok;
    }
}
