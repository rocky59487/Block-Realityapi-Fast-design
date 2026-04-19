package com.blockreality.test;

import com.blockreality.api.physics.pfsf.NativePFSFBridge;
import com.blockreality.api.physics.pfsf.VulkanComputeContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class NativeIsolationTest {

    @Test
    public void testNativeStack() {
        System.out.println("==================================================");
        System.out.println("  Block Reality - Native C++ Isolation Test");
        System.out.println("==================================================");
        
        System.out.println("\n[1] Testing Native Library Loading...");
        try {
            // Force initialization of the bridge which triggers System.load()
            Class.forName("com.blockreality.api.physics.pfsf.NativePFSFBridge");
            
            if (NativePFSFBridge.isAvailable()) {
                System.out.println("  [OK] C++ Library Loaded successfully!");
                System.out.println("  [INFO] Version: " + NativePFSFBridge.getVersion());
                
                // Let's test a simple native call
                System.out.println("\n[2] Testing Native Function Call...");
                boolean hasFeature = NativePFSFBridge.hasComputeV5();
                System.out.println("  [OK] Native call succeeded! hasComputeV5 = " + hasFeature);
            } else {
                System.out.println("  [FAIL] NativePFSFBridge reports not available.");
                fail("NativePFSFBridge is not available");
            }
        } catch (Throwable t) {
            System.out.println("  [FATAL] Native loading crashed:");
            t.printStackTrace();
            fail("Native loading crashed", t);
        }
        
        System.out.println("\n[3] Testing Vulkan Compute Context Initialization...");
        try {
            VulkanComputeContext.init();
            if (VulkanComputeContext.isAvailable()) {
                System.out.println("  [OK] Vulkan Compute Initialized successfully!");
            } else {
                System.out.println("  [FAIL] Vulkan Compute reported not available.");
                fail("Vulkan Compute is not available");
            }
        } catch (Throwable t) {
            System.out.println("  [FATAL] Vulkan Compute crashed:");
            t.printStackTrace();
            fail("Vulkan Compute crashed", t);
        }
        
        System.out.println("\n==================================================");
        System.out.println("Test Complete.");
    }
}
