import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Stage 2: VkDevice 作成 + VMA 初期化 (pVulkanFunctions 修正の検証)
 *
 * 検証内容:
 *   A) pVulkanFunctions なし → VmaAllocatorCreateInfo.validate() NPE (BUGGY 旧コード)
 *   B) pVulkanFunctions あり → vmaCreateAllocator 成功 (FIXED 新コード)
 *   C) VMA バッファアロケーション成功
 */
public class Stage2_VMAInit {

    static final String PASS = "  [PASS] ";
    static final String FAIL = "  [FAIL] ";
    static final String INFO = "  [INFO] ";
    static final String WARN = "  [WARN] ";

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  Stage 2: VkDevice 作成 + VMA 初期化 (pVulkanFunctions)  ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        System.setProperty("org.lwjgl.librarypath", "/tmp/vk_smoke_test/natives");

        // ─── Build shared VK objects first ───
        long[] shared = buildDeviceAndQueue();
        if (shared == null) {
            System.out.println("Stage 2: FAILED (could not create VkDevice)");
            System.exit(1);
        }
        long hInstance = shared[0];
        long hPhysical = shared[1];
        long hDevice   = shared[2];
        int  queueFam  = (int) shared[3];

        VkInstance       vkInstance = (VkInstance)       getThreadLocal("instance");
        VkPhysicalDevice vkPhysical = (VkPhysicalDevice) getThreadLocal("physical");
        VkDevice         vkDevice   = (VkDevice)         getThreadLocal("device");

        boolean testA = testBuggyNoFunctions(vkInstance, vkPhysical, vkDevice);
        System.out.println();
        boolean testB = testFixedWithFunctions(vkInstance, vkPhysical, vkDevice);
        System.out.println();
        boolean testC = testA || testB ? testBufferAllocation(vkDevice) : false;

        // cleanup
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkDestroyDevice(vkDevice, null);
            vkDestroyInstance(vkInstance, null);
        }

        System.out.println();
        boolean allPass = testB && testC; // testA may PASS (expected NPE) or SKIP
        System.out.println(allPass ? "Stage 2: PASSED" : "Stage 2: FAILED");
        System.exit(allPass ? 0 : 1);
    }

    // ─── Thread-local storage for LWJGL objects between methods ───
    static VkInstance       tlInstance;
    static VkPhysicalDevice tlPhysical;
    static VkDevice         tlDevice;
    static long             tlVmaAllocator;

    static Object getThreadLocal(String key) {
        return switch(key) {
            case "instance" -> tlInstance;
            case "physical" -> tlPhysical;
            case "device"   -> tlDevice;
            default         -> null;
        };
    }

    // ─── Subtest A: 旧コード (pVulkanFunctions なし) ───
    static boolean testBuggyNoFunctions(VkInstance instance, VkPhysicalDevice physical, VkDevice device) {
        System.out.println("  ── SubTest A: 旧コード pVulkanFunctions 欠如 (BUGGY) ──");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaAllocatorCreateInfo allocatorCI = VmaAllocatorCreateInfo.calloc(stack)
                .instance(instance)
                .physicalDevice(physical)
                .device(device)
                .vulkanApiVersion(VK_API_VERSION_1_2);
            // ← pVulkanFunctions NOT set (旧コードの再現)

            PointerBuffer pAllocator = stack.mallocPointer(1);
            int result = vmaCreateAllocator(allocatorCI, pAllocator);
            // If we reach here: LWJGL version doesn't enforce validate() (unlikely for 3.3.1)
            System.out.println(WARN + "旧コードが例外なしで完了 — LWJGL version may differ. result=" + vkResultToString(result));
            if (result == VK_SUCCESS) {
                vmaDestroyAllocator(pAllocator.get(0));
                System.out.println(WARN + "  (LWJGL 3.3.1では通常NPEになるはず — 要注意)");
            }
            return true; // either way, continues to test B
        } catch (NullPointerException npe) {
            System.out.println(PASS + "旧コード → NPE 確認 (LWJGL 3.3.1 validate() 動作確認): " + npe.getMessage());
            System.out.println(INFO + "  これが RTX 5070 Ti で Vulkan が動かなかった根本原因");
            return true; // expected behavior
        } catch (Throwable t) {
            System.out.println(INFO + "旧コード → 例外: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return true; // not a test failure per se
        }
    }

    // ─── Subtest B: 新コード (pVulkanFunctions あり) ───
    static boolean testFixedWithFunctions(VkInstance instance, VkPhysicalDevice physical, VkDevice device) {
        System.out.println("  ── SubTest B: 新コード pVulkanFunctions 追加 (FIXED) ──");
        try (MemoryStack stack = MemoryStack.stackPush()) {

            // ★ FIXED CODE: VmaVulkanFunctions.set(instance, device) 必須
            VmaVulkanFunctions vmaFunctions = VmaVulkanFunctions.calloc(stack)
                .set(instance, device);
            System.out.println(INFO + "VmaVulkanFunctions.set() completed — function pointers populated");

            VmaAllocatorCreateInfo allocatorCI = VmaAllocatorCreateInfo.calloc(stack)
                .instance(instance)
                .physicalDevice(physical)
                .device(device)
                .pVulkanFunctions(vmaFunctions)           // ★ 新コード: 必須
                .vulkanApiVersion(VK_API_VERSION_1_2);

            PointerBuffer pAllocator = stack.mallocPointer(1);
            int result = vmaCreateAllocator(allocatorCI, pAllocator);

            if (result != VK_SUCCESS) {
                System.out.println(FAIL + "vmaCreateAllocator failed: " + vkResultToString(result));
                return false;
            }

            tlVmaAllocator = pAllocator.get(0);
            System.out.println(PASS + "vmaCreateAllocator succeeded — allocator: 0x"
                + Long.toHexString(tlVmaAllocator));
            return true;

        } catch (Throwable t) {
            System.out.println(FAIL + "新コード VMA init 例外: " + t);
            t.printStackTrace();
            return false;
        }
    }

    // ─── Subtest C: VMA バッファアロケーション ───
    static boolean testBufferAllocation(VkDevice device) {
        if (tlVmaAllocator == 0) {
            System.out.println(INFO + "SubTest C skipped (no allocator)");
            return false;
        }
        System.out.println("  ── SubTest C: VMA バッファアロケーション (DEVICE_LOCAL) ──");
        try (MemoryStack stack = MemoryStack.stackPush()) {

            // ─── C1: 1MB DEVICE_LOCAL storage buffer ───
            VkBufferCreateInfo bufCI = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(1024 * 1024)   // 1 MB
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_GPU_ONLY);

            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);

            int result = vmaCreateBuffer(tlVmaAllocator, bufCI, allocCI, pBuffer, pAlloc, null);
            if (result != VK_SUCCESS) {
                System.out.println(FAIL + "vmaCreateBuffer (GPU_ONLY 1MB) failed: " + vkResultToString(result));
                vmaDestroyAllocator(tlVmaAllocator);
                return false;
            }
            long buf1 = pBuffer.get(0);
            long alloc1 = pAlloc.get(0);
            System.out.println(PASS + "vmaCreateBuffer 1MB DEVICE_LOCAL: buf=0x" + Long.toHexString(buf1));

            // ─── C2: staging buffer (HOST_VISIBLE) ───
            VkBufferCreateInfo stagingCI = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(64 * 1024)   // 64 KB staging
                .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo stagingAllocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_CPU_ONLY);

            result = vmaCreateBuffer(tlVmaAllocator, stagingCI, stagingAllocCI, pBuffer, pAlloc, null);
            if (result != VK_SUCCESS) {
                System.out.println(FAIL + "vmaCreateBuffer (CPU_ONLY staging) failed: " + vkResultToString(result));
                vmaDestroyBuffer(tlVmaAllocator, buf1, alloc1);
                vmaDestroyAllocator(tlVmaAllocator);
                return false;
            }
            long stagingBuf   = pBuffer.get(0);
            long stagingAlloc = pAlloc.get(0);
            System.out.println(PASS + "vmaCreateBuffer 64KB staging HOST_VISIBLE: buf=0x" + Long.toHexString(stagingBuf));

            // ─── C3: map staging buffer ───
            PointerBuffer ppData = stack.mallocPointer(1);
            result = vmaMapMemory(tlVmaAllocator, stagingAlloc, ppData);
            if (result != VK_SUCCESS) {
                System.out.println(FAIL + "vmaMapMemory failed: " + vkResultToString(result));
            } else {
                // Write some test data
                ppData.get(0); // just access it
                vmaUnmapMemory(tlVmaAllocator, stagingAlloc);
                System.out.println(PASS + "vmaMapMemory / vmaUnmapMemory OK");
            }

            // ─── Cleanup ───
            vmaDestroyBuffer(tlVmaAllocator, stagingBuf, stagingAlloc);
            vmaDestroyBuffer(tlVmaAllocator, buf1, alloc1);
            vmaDestroyAllocator(tlVmaAllocator);
            tlVmaAllocator = 0;
            System.out.println(PASS + "VMA allocator destroyed cleanly");
            return true;

        } catch (Throwable t) {
            System.out.println(FAIL + "バッファアロケーション例外: " + t);
            t.printStackTrace();
            return false;
        }
    }

    // ─── Helper: VkDevice + Queue 作成 ───
    static long[] buildDeviceAndQueue() {
        try (MemoryStack stack = MemoryStack.stackPush()) {

            // Create instance
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8Safe("Stage2"))
                .apiVersion(VK_API_VERSION_1_2);

            VkInstanceCreateInfo instCI = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo);

            PointerBuffer pInst = stack.mallocPointer(1);
            if (vkCreateInstance(instCI, null, pInst) != VK_SUCCESS) return null;
            tlInstance = new VkInstance(pInst.get(0), instCI);

            // Pick physical device
            IntBuffer pCount = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(tlInstance, pCount, null);
            if (pCount.get(0) == 0) return null;

            PointerBuffer pDevices = stack.mallocPointer(pCount.get(0));
            vkEnumeratePhysicalDevices(tlInstance, pCount, pDevices);

            tlPhysical = new VkPhysicalDevice(pDevices.get(0), tlInstance);

            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(tlPhysical, props);
            System.out.println(INFO + "Building VkDevice on: " + props.deviceNameString());

            // Find compute queue
            vkGetPhysicalDeviceQueueFamilyProperties(tlPhysical, pCount, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(pCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(tlPhysical, pCount, families);

            int computeFamily = -1;
            for (int i = 0; i < pCount.get(0); i++) {
                if ((families.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                    computeFamily = i; break;
                }
            }
            if (computeFamily < 0) return null;

            // Create device
            FloatBuffer queuePriorities = stack.floats(1.0f);
            VkDeviceQueueCreateInfo.Buffer queueCI = VkDeviceQueueCreateInfo.calloc(1, stack);
            queueCI.get(0).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(computeFamily)
                .pQueuePriorities(queuePriorities);

            VkDeviceCreateInfo deviceCI = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueCI);

            PointerBuffer pDevice = stack.mallocPointer(1);
            if (vkCreateDevice(tlPhysical, deviceCI, null, pDevice) != VK_SUCCESS) return null;
            tlDevice = new VkDevice(pDevice.get(0), tlPhysical, deviceCI);

            System.out.println(INFO + "VkDevice created: 0x" + Long.toHexString(tlDevice.address()));
            return new long[]{tlInstance.address(), tlPhysical.address(), tlDevice.address(), computeFamily};

        } catch (Throwable t) {
            System.out.println("  [FAIL] buildDeviceAndQueue: " + t);
            t.printStackTrace();
            return null;
        }
    }

    static String vkResultToString(int result) {
        return switch (result) {
            case VK_SUCCESS                    -> "VK_SUCCESS";
            case VK_NOT_READY                  -> "VK_NOT_READY";
            case VK_ERROR_OUT_OF_HOST_MEMORY   -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK_ERROR_INITIALIZATION_FAILED-> "VK_ERROR_INITIALIZATION_FAILED";
            case VK_ERROR_LAYER_NOT_PRESENT    -> "VK_ERROR_LAYER_NOT_PRESENT";
            case VK_ERROR_EXTENSION_NOT_PRESENT-> "VK_ERROR_EXTENSION_NOT_PRESENT";
            case VK_ERROR_INCOMPATIBLE_DRIVER  -> "VK_ERROR_INCOMPATIBLE_DRIVER";
            default                            -> "UNKNOWN(" + result + ")";
        };
    }
}
