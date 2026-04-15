import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

/**
 * Stage 1: Vulkan Instance + Physical Device 選取
 * 驗證：lavapipe CPU device 可被 LWJGL 正確列舉並選取
 */
public class Stage1_VkInstance {

    static final String PASS = "  [PASS] ";
    static final String FAIL = "  [FAIL] ";
    static final String INFO = "  [INFO] ";

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Stage 1: Vulkan Instance + Physical Device 選取      ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        // Extract natives from JARs (LWJGL 3.3.1 SharedLibraryLoader)
        // Set library path so LWJGL can find its .so files
        System.setProperty("org.lwjgl.librarypath", "/tmp/vk_smoke_test/natives");
        System.setProperty("org.lwjgl.util.DebugLoader", "true");

        boolean ok = run();
        System.out.println();
        System.out.println(ok ? "Stage 1: PASSED" : "Stage 1: FAILED");
        System.exit(ok ? 0 : 1);
    }

    static boolean run() {
        try (MemoryStack stack = MemoryStack.stackPush()) {

            // ─── 1a: 列舉 instance layers ───
            IntBuffer pCount = stack.mallocInt(1);
            vkEnumerateInstanceLayerProperties(pCount, null);
            System.out.println(INFO + "Available instance layers: " + pCount.get(0));

            // ─── 1b: 建立 VkInstance ───
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8Safe("VkSmokeTest"))
                .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                .pEngineName(stack.UTF8Safe("BlockReality-Test"))
                .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                .apiVersion(VK11.VK_API_VERSION_1_1);

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo);

            PointerBuffer pInstance = stack.mallocPointer(1);
            int result = vkCreateInstance(createInfo, null, pInstance);
            if (result != VK_SUCCESS) {
                System.out.println(FAIL + "vkCreateInstance failed: " + result);
                return false;
            }
            VkInstance instance = new VkInstance(pInstance.get(0), createInfo);
            System.out.println(PASS + "vkCreateInstance OK — instance handle: 0x"
                + Long.toHexString(instance.address()));

            // ─── 1c: 列舉物理裝置 ───
            vkEnumeratePhysicalDevices(instance, pCount, null);
            int deviceCount = pCount.get(0);
            System.out.println(INFO + "Physical device count: " + deviceCount);
            if (deviceCount == 0) {
                System.out.println(FAIL + "No Vulkan physical devices found");
                vkDestroyInstance(instance, null);
                return false;
            }

            PointerBuffer pDevices = stack.mallocPointer(deviceCount);
            vkEnumeratePhysicalDevices(instance, pCount, pDevices);

            VkPhysicalDevice selectedDevice = null;
            String selectedName = null;
            int selectedType = -1;

            for (int i = 0; i < deviceCount; i++) {
                VkPhysicalDevice dev = new VkPhysicalDevice(pDevices.get(i), instance);
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(dev, props);

                String name = props.deviceNameString();
                int type   = props.deviceType();
                int apiVer = props.apiVersion();

                System.out.printf("  [INFO] GPU%d: %-40s  type=%-12s  api=%d.%d.%d%n",
                    i, name, deviceTypeName(type),
                    VK_VERSION_MAJOR(apiVer), VK_VERSION_MINOR(apiVer), VK_VERSION_PATCH(apiVer));

                // 選 DISCRETE > INTEGRATED > VIRTUAL > CPU
                int score = switch (type) {
                    case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU   -> 4;
                    case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 3;
                    case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU    -> 2;
                    case VK_PHYSICAL_DEVICE_TYPE_CPU            -> 1;
                    default                                      -> 0;
                };
                if (score > selectedType) {
                    selectedType  = score;
                    selectedDevice = dev;
                    selectedName  = name;
                }
            }

            if (selectedDevice == null) {
                System.out.println(FAIL + "Device selection failed");
                vkDestroyInstance(instance, null);
                return false;
            }
            System.out.println(PASS + "Selected device: " + selectedName
                + " (type=" + deviceTypeName(typeFromScore(selectedType)) + ")");

            // ─── 1d: 確認有 compute queue family ───
            vkGetPhysicalDeviceQueueFamilyProperties(selectedDevice, pCount, null);
            int famCount = pCount.get(0);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(famCount, stack);
            vkGetPhysicalDeviceQueueFamilyProperties(selectedDevice, pCount, families);

            int computeFamily = -1;
            for (int i = 0; i < famCount; i++) {
                if ((families.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                    computeFamily = i;
                    break;
                }
            }
            if (computeFamily < 0) {
                System.out.println(FAIL + "No compute queue family found");
                vkDestroyInstance(instance, null);
                return false;
            }
            System.out.println(PASS + "Compute queue family: " + computeFamily);

            vkDestroyInstance(instance, null);
            System.out.println(PASS + "VkInstance destroyed cleanly");
            return true;

        } catch (Throwable t) {
            System.out.println(FAIL + "Exception: " + t);
            t.printStackTrace();
            return false;
        }
    }

    static String deviceTypeName(int type) {
        return switch (type) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU   -> "DISCRETE_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "INTEGRATED_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU    -> "VIRTUAL_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_CPU            -> "CPU";
            default                                      -> "OTHER(" + type + ")";
        };
    }

    static int typeFromScore(int score) {
        return switch (score) {
            case 4 -> VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
            case 3 -> VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
            case 2 -> VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU;
            case 1 -> VK_PHYSICAL_DEVICE_TYPE_CPU;
            default -> VK_PHYSICAL_DEVICE_TYPE_OTHER;
        };
    }
}
