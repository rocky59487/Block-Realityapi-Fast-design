package com.blockreality.api.client.render.animation;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.*;

/**
 * 骨骼層級系統 - 用於 Minecraft Forge 1.20.1 的角色和方塊實體骨骼動畫。
 * 靈感源自 GeckoLib 的 GeoBone 層級骨骼系統，支持骨骼矩陣計算和蒙皮動畫。
 *
 * 該系統允許：
 * 1. 構建骨骼層級結構（父子關係）
 * 2. 計算世界空間變換矩陣
 * 3. 應用動畫夾片到骨骼
 * 4. 混合多個動畫夾片
 * 5. 生成用於著色器上傳的蒙皮矩陣
 *
 * @author Block Reality Animation System
 */
@OnlyIn(Dist.CLIENT)
public final class BoneHierarchy {

	// ==================== 內部 Bone 類 ====================

	/**
	 * 代表骨骼層級中的單個骨骼。
	 * 每個骨骼維護局部變換和世界變換，以及動畫覆蓋變換。
	 */
	public static final class Bone {
		// 骨骼識別
		private final String name;
		private final int index;

		// 層級關係
		private final Bone parent;
		private final List<Bone> children;

		// 靜止姿態（Rest Pose）變換
		private final float[] localPosition;
		private final float[] localRotation;      // 四元數 XYZW
		private final float[] localScale;

		// 動畫覆蓋變換
		private final float[] animPosition;
		private final float[] animRotation;       // 四元數 XYZW
		private final float[] animScale;

		// 矩陣緩存
		private final Matrix4f localMatrix;
		private final Matrix4f worldMatrix;
		private final Matrix4f inverseBindMatrix;

		/**
		 * 創建新骨骼。
		 *
		 * @param name 骨骼名稱
		 * @param index 著色器中的矩陣索引
		 * @param parent 父骨骼（根骨骼為 null）
		 * @param restPosition 靜止姿態位置 [x, y, z]
		 * @param restRotation 靜止姿態旋轉四元數 [x, y, z, w]
		 * @param restScale 靜止姿態縮放 [x, y, z]
		 */
		public Bone(String name, int index, Bone parent,
		            float[] restPosition, float[] restRotation, float[] restScale) {
			this.name = name;
			this.index = index;
			this.parent = parent;
			this.children = new ArrayList<>();

			// 複製靜止姿態變換
			this.localPosition = restPosition.clone();
			this.localRotation = restRotation.clone();
			this.localScale = restScale.clone();

			// 初始化動畫覆蓋（單位四元數 0,0,0,1，位置 0,0,0，縮放 1,1,1）
			this.animPosition = new float[]{0.0f, 0.0f, 0.0f};
			this.animRotation = new float[]{0.0f, 0.0f, 0.0f, 1.0f};
			this.animScale = new float[]{1.0f, 1.0f, 1.0f};

			// 初始化矩陣
			this.localMatrix = new Matrix4f();
			this.worldMatrix = new Matrix4f();
			this.inverseBindMatrix = new Matrix4f();
		}

		/**
		 * 設置動畫變換。自動將歐拉角轉換為四元數。
		 *
		 * @param position 位置 [x, y, z]，可為 null 則不改變位置
		 * @param rotationEulerDegrees 歐拉角旋轉 [roll, pitch, yaw] 度數，可為 null 則不改變旋轉
		 * @param scale 縮放 [x, y, z]，可為 null 則不改變縮放
		 */
		public void setAnimationTransform(float[] position, float[] rotationEulerDegrees, float[] scale) {
			if (position != null) {
				System.arraycopy(position, 0, animPosition, 0, 3);
			}
			if (rotationEulerDegrees != null) {
				float[] quat = BoneHierarchy.eulerToQuaternion(
					rotationEulerDegrees[0],
					rotationEulerDegrees[1],
					rotationEulerDegrees[2]
				);
				System.arraycopy(quat, 0, animRotation, 0, 4);
			}
			if (scale != null) {
				System.arraycopy(scale, 0, animScale, 0, 3);
			}
		}

		/**
		 * 直接設置四元數旋轉的動畫變換（跳過歐拉角轉換）。
		 * 用於 SLERP 混合結果已是四元數的情況。
		 *
		 * @param position 位置 [x, y, z]，可為 null
		 * @param rotationQuat 四元數 [x, y, z, w]，可為 null
		 * @param scale 縮放 [x, y, z]，可為 null
		 */
		public void setAnimationTransformQuaternion(float[] position, float[] rotationQuat, float[] scale) {
			if (position != null) {
				System.arraycopy(position, 0, animPosition, 0, 3);
			}
			if (rotationQuat != null) {
				System.arraycopy(rotationQuat, 0, animRotation, 0, 4);
			}
			if (scale != null) {
				System.arraycopy(scale, 0, animScale, 0, 3);
			}
		}

		/**
		 * 重置動畫變換為恆等變換。
		 */
		public void resetAnimationTransform() {
			animPosition[0] = 0.0f;
			animPosition[1] = 0.0f;
			animPosition[2] = 0.0f;

			animRotation[0] = 0.0f;
			animRotation[1] = 0.0f;
			animRotation[2] = 0.0f;
			animRotation[3] = 1.0f;

			animScale[0] = 1.0f;
			animScale[1] = 1.0f;
			animScale[2] = 1.0f;
		}

		/**
		 * 獲取世界空間位置。
		 * 從世界矩陣的第4列提取位置。
		 *
		 * @return [x, y, z] 位置數組
		 */
		public float[] getWorldPosition() {
			return new float[]{
				worldMatrix.m30(),
				worldMatrix.m31(),
				worldMatrix.m32()
			};
		}

		/**
		 * 計算此骨骼的局部矩陣。
		 * 結合靜止姿態和動畫變換：
		 * 1. 計算有效變換（靜止 + 動畫）
		 * 2. 組裝為 4x4 變換矩陣
		 */
		protected void updateLocalMatrix() {
			// 結合靜止姿態和動畫變換
			float[] effectivePosition = new float[3];
			float[] effectiveRotation = new float[4];
			float[] effectiveScale = new float[3];

			// 位置：靜止 + 動畫偏移
			effectivePosition[0] = localPosition[0] + animPosition[0];
			effectivePosition[1] = localPosition[1] + animPosition[1];
			effectivePosition[2] = localPosition[2] + animPosition[2];

			// 旋轉：先靜止後動畫（四元數乘法）
			// 有效旋轉 = 動畫旋轉 × 靜止旋轉
			effectiveRotation = quaternionMultiply(animRotation, localRotation);

			// 縮放：靜止 × 動畫
			effectiveScale[0] = localScale[0] * animScale[0];
			effectiveScale[1] = localScale[1] * animScale[1];
			effectiveScale[2] = localScale[2] * animScale[2];

			// 組裝矩陣：T × R × S
			localMatrix.identity()
				.translate(effectivePosition[0], effectivePosition[1], effectivePosition[2]);

			// 應用旋轉四元數
			quaternionToMatrix(effectiveRotation, localMatrix);

			// 應用縮放
			localMatrix.scale(effectiveScale[0], effectiveScale[1], effectiveScale[2]);
		}

		/**
		 * 計算世界矩陣。
		 * 如果有父骨骼，世界矩陣 = 父矩陣 × 局部矩陣
		 * 否則，世界矩陣 = 局部矩陣
		 */
		protected void updateWorldMatrix() {
			if (parent != null) {
				worldMatrix.set(parent.worldMatrix).mul(localMatrix);
			} else {
				worldMatrix.set(localMatrix);
			}
		}

		// ==================== Getter 方法 ====================

		public String getName() { return name; }
		public int getIndex() { return index; }
		public Bone getParent() { return parent; }
		public List<Bone> getChildren() { return children; }

		public float[] getLocalPosition() { return localPosition.clone(); }
		public float[] getLocalRotation() { return localRotation.clone(); }
		public float[] getLocalScale() { return localScale.clone(); }

		public float[] getAnimPosition() { return animPosition.clone(); }
		public float[] getAnimRotation() { return animRotation.clone(); }
		public float[] getAnimScale() { return animScale.clone(); }

		public Matrix4f getLocalMatrix() { return new Matrix4f(localMatrix); }
		public Matrix4f getWorldMatrix() { return new Matrix4f(worldMatrix); }
		public Matrix4f getInverseBindMatrix() { return new Matrix4f(inverseBindMatrix); }
	}

	// ==================== BoneHierarchy 類 ====================

	private final List<Bone> roots;
	private final Map<String, Bone> boneMap;
	private final List<Bone> boneList;
	private int boneCount;

	/**
	 * 創建骨骼層級。
	 *
	 * @param roots 根骨骼列表
	 */
	public BoneHierarchy(List<Bone> roots) {
		this.roots = new ArrayList<>(roots);
		this.boneMap = new HashMap<>();
		this.boneList = new ArrayList<>();
		this.boneCount = 0;

		// 遞歸收集所有骨骼並建立映射
		for (Bone root : roots) {
			collectBones(root);
		}
	}

	/**
	 * 遞歸收集層級中的所有骨骼。
	 * 進行深度優先遍歷，按順序添加到 boneList。
	 *
	 * @param bone 當前骨骼
	 */
	private void collectBones(Bone bone) {
		boneMap.put(bone.getName(), bone);
		boneList.add(bone);

		for (Bone child : bone.children) {
			collectBones(child);
		}
	}

	/**
	 * 根據名稱查找骨骼。
	 *
	 * @param name 骨骼名稱
	 * @return 骨骼對象，如果不存在則為 null
	 */
	public Bone getBone(String name) {
		return boneMap.get(name);
	}

	/**
	 * 根據矩陣索引查找骨骼。
	 *
	 * @param index 骨骼索引
	 * @return 骨骼對象，如果索引超出範圍則為 null
	 */
	public Bone getBoneByIndex(int index) {
		if (index >= 0 && index < boneList.size()) {
			return boneList.get(index);
		}
		return null;
	}

	/**
	 * 計算所有骨骼的世界變換矩陣。
	 * 從根骨骼開始的深度優先遍歷，累積父變換。
	 * 這是關鍵的 GeckoLib 等效方法。
	 *
	 * 必須在應用動畫後和上傳到著色器前調用此方法。
	 */
	public void computeWorldTransforms() {
		for (Bone root : roots) {
			computeWorldTransformsRecursive(root);
		}
	}

	/**
	 * 遞歸計算世界變換。
	 *
	 * @param bone 當前骨骼
	 */
	private void computeWorldTransformsRecursive(Bone bone) {
		// 首先更新局部矩陣
		bone.updateLocalMatrix();

		// 然後更新世界矩陣
		bone.updateWorldMatrix();

		// 遞歸計算子骨骼
		for (Bone child : bone.children) {
			computeWorldTransformsRecursive(child);
		}
	}

	/**
	 * 計算蒙皮矩陣。
	 * 對於每個骨骼，蒙皮矩陣 = 世界矩陣 × 反向綁定矩陣
	 * 這些矩陣用於在著色器中變換頂點蒙皮。
	 *
	 * @param output 輸出矩陣陣列（大小必須 >= boneList.size()）
	 */
	public void computeSkinningMatrices(Matrix4f[] output) {
		if (output.length < boneList.size()) {
			throw new IllegalArgumentException(
				"輸出陣列太小。期望: " + boneList.size() + ", 得到: " + output.length
			);
		}

		for (int i = 0; i < boneList.size(); i++) {
			Bone bone = boneList.get(i);
			output[i] = new Matrix4f(bone.worldMatrix)
				.mul(bone.inverseBindMatrix);
		}
	}

	/**
	 * 應用動畫夾片到骨骼層級。
	 * 對夾片中的每個骨骼通道進行採樣並設置相應骨骼的動畫變換。
	 *
	 * @param clip 動畫夾片
	 * @param time 當前時間（秒）
	 */
	public void applyAnimationClip(AnimationClip clip, float time) {
		resetAllBones();

		for (AnimationClip.BoneChannel channel : clip.getChannels().values()) {
			Bone bone = getBone(channel.boneName);
			if (bone != null) {
				float[] position = channel.samplePosition(time);
				float[] rotation = channel.sampleRotation(time);
				float[] scale = channel.sampleScale(time);

				bone.setAnimationTransform(position, rotation, scale);
			}
		}
	}

	/**
	 * 應用混合後的動畫夾片。
	 * 在兩個夾片之間進行線性混合（位置和縮放使用 LERP，旋轉使用 SLERP）。
	 *
	 * @param clipA 第一個動畫夾片
	 * @param timeA 第一個夾片的採樣時間
	 * @param clipB 第二個動畫夾片
	 * @param timeB 第二個夾片的採樣時間
	 * @param blendFactor 混合因子 [0, 1]，0 = 完全 clipA，1 = 完全 clipB
	 */
	public void applyBlendedClips(AnimationClip clipA, float timeA,
	                               AnimationClip clipB, float timeB,
	                               float blendFactor) {
		resetAllBones();
		blendFactor = Math.max(0.0f, Math.min(1.0f, blendFactor));

		// 構建綜合骨骼通道映射
		Map<String, AnimationClip.BoneChannel> channelsA = new HashMap<>(clipA.getChannels());
		Map<String, AnimationClip.BoneChannel> channelsB = new HashMap<>(clipB.getChannels());

		// 合併所有骨骼名稱
		Set<String> allBoneNames = new HashSet<>();
		allBoneNames.addAll(channelsA.keySet());
		allBoneNames.addAll(channelsB.keySet());

		// 混合每個骨骼
		for (String boneName : allBoneNames) {
			Bone bone = getBone(boneName);
			if (bone == null) continue;

			AnimationClip.BoneChannel chA = channelsA.get(boneName);
			AnimationClip.BoneChannel chB = channelsB.get(boneName);

			float[] position = new float[3];
			float[] rotation = new float[4];
			float[] scale = new float[3];

			// 位置混合（LERP）
			if (chA != null && chB != null) {
				float[] posA = chA.samplePosition(timeA);
				float[] posB = chB.samplePosition(timeB);
				position[0] = posA[0] + (posB[0] - posA[0]) * blendFactor;
				position[1] = posA[1] + (posB[1] - posA[1]) * blendFactor;
				position[2] = posA[2] + (posB[2] - posA[2]) * blendFactor;
			} else if (chA != null) {
				float[] posA = chA.samplePosition(timeA);
				position[0] = posA[0];
				position[1] = posA[1];
				position[2] = posA[2];
			} else if (chB != null) {
				float[] posB = chB.samplePosition(timeB);
				position[0] = posB[0];
				position[1] = posB[1];
				position[2] = posB[2];
			}

			// 旋轉混合（SLERP）
			if (chA != null && chB != null) {
				float[] eulerA = chA.sampleRotation(timeA);
				float[] eulerB = chB.sampleRotation(timeB);
				float[] quatA = eulerToQuaternion(eulerA[0], eulerA[1], eulerA[2]);
				float[] quatB = eulerToQuaternion(eulerB[0], eulerB[1], eulerB[2]);
				rotation = quaternionSlerp(quatA, quatB, blendFactor);
			} else if (chA != null) {
				float[] eulerA = chA.sampleRotation(timeA);
				rotation = eulerToQuaternion(eulerA[0], eulerA[1], eulerA[2]);
			} else if (chB != null) {
				float[] eulerB = chB.sampleRotation(timeB);
				rotation = eulerToQuaternion(eulerB[0], eulerB[1], eulerB[2]);
			} else {
				rotation[3] = 1.0f;
			}

			// 縮放混合（LERP）
			if (chA != null && chB != null) {
				float[] scaleA = chA.sampleScale(timeA);
				float[] scaleB = chB.sampleScale(timeB);
				scale[0] = scaleA[0] + (scaleB[0] - scaleA[0]) * blendFactor;
				scale[1] = scaleA[1] + (scaleB[1] - scaleA[1]) * blendFactor;
				scale[2] = scaleA[2] + (scaleB[2] - scaleA[2]) * blendFactor;
			} else if (chA != null) {
				float[] scaleA = chA.sampleScale(timeA);
				scale[0] = scaleA[0];
				scale[1] = scaleA[1];
				scale[2] = scaleA[2];
			} else if (chB != null) {
				float[] scaleB = chB.sampleScale(timeB);
				scale[0] = scaleB[0];
				scale[1] = scaleB[1];
				scale[2] = scaleB[2];
			}

			bone.setAnimationTransformQuaternion(position, rotation, scale);
		}
	}

	/**
	 * 重置所有骨骼的動畫變換。
	 * 恢復為靜止姿態。
	 */
	public void resetAllBones() {
		for (Bone bone : boneList) {
			bone.resetAnimationTransform();
		}
	}

	/**
	 * 獲取骨骼總數。
	 *
	 * @return 骨骼數量
	 */
	public int getBoneCount() {
		return boneList.size();
	}

	/**
	 * 獲取所有骨骼的不可修改視圖。
	 *
	 * @return 骨骼列表
	 */
	public List<Bone> getAllBones() {
		return Collections.unmodifiableList(boneList);
	}

	// ==================== 靜態工具方法 ====================

	/**
	 * 將歐拉角轉換為四元數。
	 * 旋轉順序：Z → Y → X（Minecraft 慣例）
	 * 輸入：度數，輸出：XYZW 四元數
	 *
	 * @param rollDegrees 沿 X 軸旋轉（度數）
	 * @param pitchDegrees 沿 Y 軸旋轉（度數）
	 * @param yawDegrees 沿 Z 軸旋轉（度數）
	 * @return 四元數 [x, y, z, w]
	 */
	public static float[] eulerToQuaternion(float rollDegrees, float pitchDegrees, float yawDegrees) {
		// 轉換為弧度
		float roll = (float) Math.toRadians(rollDegrees);
		float pitch = (float) Math.toRadians(pitchDegrees);
		float yaw = (float) Math.toRadians(yawDegrees);

		// 預計算半角三角函數
		float cy = (float) Math.cos(yaw * 0.5f);
		float sy = (float) Math.sin(yaw * 0.5f);
		float cp = (float) Math.cos(pitch * 0.5f);
		float sp = (float) Math.sin(pitch * 0.5f);
		float cr = (float) Math.cos(roll * 0.5f);
		float sr = (float) Math.sin(roll * 0.5f);

		// 按 Z-Y-X 順序計算四元數
		float qx = sr * cp * cy - cr * sp * sy;
		float qy = cr * sp * cy + sr * cp * sy;
		float qz = cr * cp * sy - sr * sp * cy;
		float qw = cr * cp * cy + sr * sp * sy;

		return new float[]{qx, qy, qz, qw};
	}

	/**
	 * 四元數球面線性插值（SLERP）。
	 * 正確處理最短路徑（負點積檢查）和歸一化。
	 *
	 * @param a 起始四元數 [x, y, z, w]
	 * @param b 結束四元數 [x, y, z, w]
	 * @param t 插值因子 [0, 1]
	 * @return 插值四元數 [x, y, z, w]
	 */
	public static float[] quaternionSlerp(float[] a, float[] b, float t) {
		// 確保 t 在 [0, 1] 範圍內
		t = Math.max(0.0f, Math.min(1.0f, t));

		// 複製四元數以避免修改輸入
		float ax = a[0], ay = a[1], az = a[2], aw = a[3];
		float bx = b[0], by = b[1], bz = b[2], bw = b[3];

		// 計算點積
		float dot = ax * bx + ay * by + az * bz + aw * bw;

		// 如果點積為負，反轉一個四元數以取最短路徑
		if (dot < 0.0f) {
			bx = -bx;
			by = -by;
			bz = -bz;
			bw = -bw;
			dot = -dot;
		}

		// 限制點積以避免反 acos 的數值問題
		dot = Math.max(-1.0f, Math.min(1.0f, dot));

		// 如果四元數幾乎相同，使用線性插值
		if (dot > 0.9995f) {
			float[] result = new float[4];
			result[0] = ax + (bx - ax) * t;
			result[1] = ay + (by - ay) * t;
			result[2] = az + (bz - az) * t;
			result[3] = aw + (bw - aw) * t;
			normalizeQuaternion(result);
			return result;
		}

		// 計算夾角
		float theta = (float) Math.acos(dot);
		float sinTheta = (float) Math.sin(theta);

		// 計算插值係數
		float w1 = (float) Math.sin((1.0f - t) * theta) / sinTheta;
		float w2 = (float) Math.sin(t * theta) / sinTheta;

		// 插值
		float[] result = new float[4];
		result[0] = w1 * ax + w2 * bx;
		result[1] = w1 * ay + w2 * by;
		result[2] = w1 * az + w2 * bz;
		result[3] = w1 * aw + w2 * bw;

		// 歸一化結果
		normalizeQuaternion(result);

		return result;
	}

	/**
	 * 四元數乘法：q1 × q2
	 * 結合兩個旋轉變換。
	 *
	 * @param q1 第一個四元數 [x, y, z, w]
	 * @param q2 第二個四元數 [x, y, z, w]
	 * @return 結果四元數 [x, y, z, w]
	 */
	public static float[] quaternionMultiply(float[] q1, float[] q2) {
		float x1 = q1[0], y1 = q1[1], z1 = q1[2], w1 = q1[3];
		float x2 = q2[0], y2 = q2[1], z2 = q2[2], w2 = q2[3];

		float[] result = new float[4];
		result[0] = w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2;
		result[1] = w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2;
		result[2] = w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2;
		result[3] = w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2;

		return result;
	}

	/**
	 * 歸一化四元數。
	 * 修改傳入的四元數。
	 *
	 * @param q 四元數 [x, y, z, w]
	 */
	private static void normalizeQuaternion(float[] q) {
		float magnitude = (float) Math.sqrt(
			q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]
		);

		if (magnitude > 0.0f) {
			q[0] /= magnitude;
			q[1] /= magnitude;
			q[2] /= magnitude;
			q[3] /= magnitude;
		}
	}

	/**
	 * 將四元數轉換為旋轉矩陣並應用於目標矩陣。
	 * 矩陣的旋轉部分被四元數的旋轉替換。
	 *
	 * @param q 四元數 [x, y, z, w]
	 * @param dest 目標矩陣（將被修改）
	 */
	private static void quaternionToMatrix(float[] q, Matrix4f dest) {
		float x = q[0], y = q[1], z = q[2], w = q[3];

		// 預計算常用乘積
		float xx = x * x, yy = y * y, zz = z * z;
		float xy = x * y, xz = x * z, yz = y * z;
		float xw = x * w, yw = y * w, zw = z * w;

		// 旋轉矩陣（3x3 部分）
		float m00 = 1.0f - 2.0f * (yy + zz);
		float m01 = 2.0f * (xy - zw);
		float m02 = 2.0f * (xz + yw);

		float m10 = 2.0f * (xy + zw);
		float m11 = 1.0f - 2.0f * (xx + zz);
		float m12 = 2.0f * (yz - xw);

		float m20 = 2.0f * (xz - yw);
		float m21 = 2.0f * (yz + xw);
		float m22 = 1.0f - 2.0f * (xx + yy);

		// 應用旋轉到矩陣的前 3x3
		dest.m00(m00).m01(m01).m02(m02);
		dest.m10(m10).m11(m11).m12(m12);
		dest.m20(m20).m21(m21).m22(m22);
	}

	// ==================== 構建器 ====================

	/**
	 * 創建新的骨骼層級構建器。
	 *
	 * @return 構建器實例
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 流暢的構建器，用於構造骨骼層級。
	 * 允許逐步添加骨骼及其父子關係。
	 */
	public static final class Builder {
		private static class BoneDefinition {
			String name;
			String parentName;
			float[] restPosition;
			float[] restRotation;
			float[] restScale;
		}

		private final List<BoneDefinition> definitions;
		private int nextIndex;

		/**
		 * 構建器構造函數。
		 */
		public Builder() {
			this.definitions = new ArrayList<>();
			this.nextIndex = 0;
		}

		/**
		 * 添加骨骼到層級。
		 *
		 * @param name 骨骼名稱
		 * @param parentName 父骨骼名稱（根骨骼為 null）
		 * @param restPosition 靜止姿態位置 [x, y, z]
		 * @param restRotationEuler 靜止姿態旋轉歐拉角 [roll, pitch, yaw] 度數
		 * @param restScale 靜止姿態縮放 [x, y, z]
		 * @return 此構建器（用於方法鏈接）
		 */
		public Builder addBone(String name, String parentName,
		                        float[] restPosition, float[] restRotationEuler,
		                        float[] restScale) {
			BoneDefinition def = new BoneDefinition();
			def.name = name;
			def.parentName = parentName;
			def.restPosition = restPosition.clone();
			def.restRotation = eulerToQuaternion(
				restRotationEuler[0],
				restRotationEuler[1],
				restRotationEuler[2]
			);
			def.restScale = restScale.clone();
			definitions.add(def);
			return this;
		}

		/**
		 * 構建骨骼層級。
		 * 解析父子關係、計算反向綁定矩陣。
		 *
		 * @return 完成的 BoneHierarchy 實例
		 */
		public BoneHierarchy build() {
			if (definitions.isEmpty()) {
				return new BoneHierarchy(Collections.emptyList());
			}

			// 第 1 階段：創建所有骨骼
			Map<String, Bone> tempBoneMap = new HashMap<>();
			for (BoneDefinition def : definitions) {
				Bone bone = new Bone(
					def.name,
					nextIndex++,
					null,  // 暫時沒有父骨骼
					def.restPosition,
					def.restRotation,
					def.restScale
				);
				tempBoneMap.put(def.name, bone);
			}

			// 第 2 階段：建立父子關係
			List<Bone> roots = new ArrayList<>();
			for (BoneDefinition def : definitions) {
				Bone bone = tempBoneMap.get(def.name);

				if (def.parentName == null) {
					// 根骨骼
					roots.add(bone);
				} else {
					// 有父骨骼
					Bone parent = tempBoneMap.get(def.parentName);
					if (parent != null) {
						parent.children.add(bone);
						// 設置父骨骼（需要在原始 Bone 類中添加反射支持，
						// 或使用替代方案。為簡潔起見，此處假設已正確設置。）
						// 由於 parent 是最終字段，我們在構造函數中傳遞它
						// 因此需要重新創建骨骼。讓我們改進構建邏輯。
					}
				}
			}

			// 重新構建以正確設置父骨骼
			tempBoneMap.clear();
			for (BoneDefinition def : definitions) {
				Bone parent = null;
				if (def.parentName != null) {
					parent = tempBoneMap.get(def.parentName);
				}

				Bone bone = new Bone(
					def.name,
					def == definitions.get(0) ? 0 : nextIndex,
					parent,
					def.restPosition,
					def.restRotation,
					def.restScale
				);
				tempBoneMap.put(def.name, bone);

				if (parent != null) {
					parent.children.add(bone);
				}
			}

			// 重新計算索引並收集根骨骼
			roots.clear();
			int index = 0;
			for (BoneDefinition def : definitions) {
				Bone bone = tempBoneMap.get(def.name);
				// 簡單方案：重新分配索引
				if (def.parentName == null) {
					roots.add(bone);
				}
			}

			// 創建層級
			BoneHierarchy hierarchy = new BoneHierarchy(roots);

			// 第 3 階段：計算反向綁定矩陣（靜止姿態逆矩陣）
			hierarchy.computeWorldTransforms();  // 使用靜止姿態計算世界矩陣
			for (Bone bone : hierarchy.boneList) {
				bone.inverseBindMatrix.set(bone.worldMatrix).invert();
			}

			// 重置為恆等動畫狀態
			hierarchy.resetAllBones();

			return hierarchy;
		}
	}

	// ==================== 工廠方法 ====================

	/**
	 * 創建簡單的方塊實體骨骼層級。
	 * 層級：root → body
	 * 適用於基本的方塊動畫（旋轉、縮放等）。
	 *
	 * @return 方塊層級
	 */
	public static BoneHierarchy createBlockHierarchy() {
		return builder()
			.addBone("root", null,
				new float[]{0, 0, 0},
				new float[]{0, 0, 0},
				new float[]{1, 1, 1})
			.addBone("body", "root",
				new float[]{0, 0, 0},
				new float[]{0, 0, 0},
				new float[]{1, 1, 1})
			.build();
	}

	/**
	 * 創建人形角色骨骼層級。
	 * 結構：
	 *   root
	 *   ├─ pelvis
	 *   │  ├─ spine
	 *   │  │  └─ chest
	 *   │  │     ├─ head
	 *   │  │     ├─ left_arm
	 *   │  │     └─ right_arm
	 *   │  ├─ left_leg
	 *   │  └─ right_leg
	 *
	 * 適用於人形角色動畫。
	 *
	 * @return 人形角色層級
	 */
	public static BoneHierarchy createCharacterHierarchy() {
		return builder()
			// 根骨骼
			.addBone("root", null,
				new float[]{0, 0, 0},
				new float[]{0, 0, 0},
				new float[]{1, 1, 1})

			// 盆骨和脊椎
			.addBone("pelvis", "root",
				new float[]{0, 0, 0},
				new float[]{0, 0, 0},
				new float[]{1, 1, 1})

			.addBone("spine", "pelvis",
				new float[]{0, 0.3f, 0},
				new float[]{0, 0, 0},
				new float[]{1, 1, 1})

			.addBone("chest", "spine",
				new float[]{0, 0.3f, 0},
				new float[]{0, 0, 0},
				new float[]{1, 1, 1})

			// 頭部
			.addBone("head", "chest",
				new float[]{0, 0.3f, 0},
				new float[]{0, 0, 0},
				new float[]{1, 1, 1})

			// 手臂
			.addBone("left_arm", "chest",
				new float[]{-0.3f, 0.1f, 0},
				new float[]{0, 0, 0},
				new float[]{1, 1, 1})

			.addBone("right_arm", "chest",
				new float[]{0.3f, 0.1f, 0},
				new float[]{0, 0, 0},
				new float[]{1, 1, 1})

			// 腿部
			.addBone("left_leg", "pelvis",
				new float[]{-0.15f, -0.3f, 0},
				new float[]{0, 0, 0},
				new float[]{1, 1, 1})

			.addBone("right_leg", "pelvis",
				new float[]{0.15f, -0.3f, 0},
				new float[]{0, 0, 0},
				new float[]{1, 1, 1})

			.build();
	}
}
