/**
 * ifc-structural-export.ts — Block Reality → IFC 4.x Structural Export
 *
 * Converts a set of BlueprintBlocks (Minecraft structural blocks with
 * physics metadata) into a semantically rich IFC 4 file containing:
 *
 *   1. IFC spatial hierarchy: Project → Site → Building → Storey
 *   2. Structural elements: IFCCOLUMN / IFCBEAM / IFCWALL / IFCSLAB
 *      classified by material and connectivity analysis
 *   3. Material properties: Rcomp (MPa), Rtens (MPa), E modulus (GPa→Pa)
 *      encoded as IfcMechanicalMaterialProperties
 *   4. Custom property sets: Pset_BlockReality_Structural
 *      containing stressLevel (0-1) and utilization ratio (%)
 *   5. Box geometry per block (1×1×1 m IFCEXTRUDEDAREASOLID)
 *
 * Competitive advantage:
 *   ✦ Only Minecraft structural mod exporting semantically valid IFC 4
 *   ✦ Per-element utilization data readable in Autodesk Revit / Tekla / OpenBIM
 *   ✦ Material strength properties importable into structural FEA tools
 *
 * @module ifc-structural-export
 */

import * as fs from 'node:fs';
import type { BlueprintBlock } from '../types.js';
import {
  IfcWriter,
  ifcGuid,
  str, real, int, enumVal, ref, refList, optRef, typed, NULL,
} from './ifc-writer.js';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface Ifc4ExportOptions {
  outputPath: string;
  projectName?: string;
  authorOrg?: string;
  /** Scale: Minecraft blocks are 1m × 1m × 1m in real-world coordinates */
  blockSizeM?: number;
  /** Include per-block geometry (false → materials/properties only, faster) */
  includeGeometry?: boolean;
}

export interface Ifc4ExportResult {
  success: boolean;
  outputPath: string;
  blockCount: number;
  elementCount: number;
  materialCount: number;
  columnCount: number;
  beamCount: number;
  wallCount: number;
  slabCount: number;
  maxStressLevel: number;
  maxUtilization: number;
}

/** Structural element role classification */
type StructuralRole = 'COLUMN' | 'BEAM' | 'WALL' | 'SLAB' | 'GENERIC';

/** Material properties known to Block Reality */
interface MaterialDef {
  id: string;
  displayName: string;
  /** Compressive strength (MPa) */
  rcomp: number;
  /** Tensile strength (MPa) */
  rtens: number;
  /** Young's modulus (GPa) */
  youngsModulus: number;
  /** Density (kg/m³) */
  density: number;
  /** Default structural role */
  defaultRole: StructuralRole;
}

// ─── Material Database ────────────────────────────────────────────────────────
// Mirrors DefaultMaterial registry values from Java api/material/DefaultMaterial.java

const MATERIAL_DB: Record<string, MaterialDef> = {
  concrete: {
    id: 'concrete', displayName: 'Reinforced Concrete',
    rcomp: 30.0, rtens: 3.0, youngsModulus: 30.0, density: 2400,
    defaultRole: 'WALL',
  },
  concrete_hpc: {
    id: 'concrete_hpc', displayName: 'High-Performance Concrete',
    rcomp: 80.0, rtens: 8.0, youngsModulus: 35.0, density: 2500,
    defaultRole: 'COLUMN',
  },
  rebar: {
    id: 'rebar', displayName: 'Reinforcing Bar (Steel)',
    rcomp: 420.0, rtens: 420.0, youngsModulus: 200.0, density: 7850,
    defaultRole: 'BEAM',
  },
  steel: {
    id: 'steel', displayName: 'Structural Steel',
    rcomp: 355.0, rtens: 355.0, youngsModulus: 210.0, density: 7850,
    defaultRole: 'BEAM',
  },
  steel_hss: {
    id: 'steel_hss', displayName: 'Hollow Structural Section Steel',
    rcomp: 350.0, rtens: 350.0, youngsModulus: 210.0, density: 7850,
    defaultRole: 'COLUMN',
  },
  timber: {
    id: 'timber', displayName: 'Structural Timber (GL30h)',
    rcomp: 24.0, rtens: 21.0, youngsModulus: 13.0, density: 480,
    defaultRole: 'BEAM',
  },
  masonry: {
    id: 'masonry', displayName: 'Masonry / Brick',
    rcomp: 6.0, rtens: 0.2, youngsModulus: 4.0, density: 1800,
    defaultRole: 'WALL',
  },
  glass_struct: {
    id: 'glass_struct', displayName: 'Structural Glass',
    rcomp: 250.0, rtens: 45.0, youngsModulus: 70.0, density: 2500,
    defaultRole: 'WALL',
  },
  aluminum: {
    id: 'aluminum', displayName: 'Structural Aluminum',
    rcomp: 270.0, rtens: 270.0, youngsModulus: 70.0, density: 2700,
    defaultRole: 'BEAM',
  },
  default: {
    id: 'default', displayName: 'Unknown Material',
    rcomp: 20.0, rtens: 2.0, youngsModulus: 25.0, density: 2000,
    defaultRole: 'GENERIC',
  },
};

function getMaterialDef(materialId: string): MaterialDef {
  return MATERIAL_DB[materialId] ?? MATERIAL_DB['default']!;
}

// ─── Structural Role Classification ──────────────────────────────────────────

/**
 * Classify each block's structural role using spatial neighbor analysis.
 *
 * Algorithm:
 *   1. Build a set of occupied positions for O(1) neighbor lookup
 *   2. Per block: count vertical (Y) and horizontal (X/Z) neighbors
 *   3. isAnchored=true → always COLUMN (fixed support)
 *   4. Vertically isolated (no blocks above/below) → SLAB if horizontal neighbors,
 *      else BEAM if in a linear horizontal run
 *   5. Primarily vertical connectivity → COLUMN
 *   6. Primarily horizontal (X or Z run) → BEAM
 *   7. Planar cluster → WALL
 *   8. Fall back to material defaultRole
 */
function classifyBlocks(blocks: BlueprintBlock[]): StructuralRole[] {
  // Build position set for O(1) lookup
  const occupied = new Set<string>();
  for (const b of blocks) {
    occupied.add(`${b.relX},${b.relY},${b.relZ}`);
  }

  const has = (x: number, y: number, z: number): boolean =>
    occupied.has(`${x},${y},${z}`);

  return blocks.map(b => {
    const { relX: x, relY: y, relZ: z, rMaterialId, isAnchored } = b;

    // Anchored blocks are structural columns (fixed supports)
    if (isAnchored) return 'COLUMN';

    const matDef = getMaterialDef(rMaterialId ?? 'default');

    // Neighbor counts
    const above = has(x, y + 1, z) ? 1 : 0;
    const below = has(x, y - 1, z) ? 1 : 0;
    const xPos  = has(x + 1, y, z) ? 1 : 0;
    const xNeg  = has(x - 1, y, z) ? 1 : 0;
    const zPos  = has(x, y, z + 1) ? 1 : 0;
    const zNeg  = has(x, y, z - 1) ? 1 : 0;

    const vertConnections  = above + below;
    const horizX = xPos + xNeg;
    const horizZ = zPos + zNeg;
    const horizConnections = horizX + horizZ;

    // Pure vertical → column
    if (vertConnections >= 2 && horizConnections === 0) return 'COLUMN';
    if (vertConnections >= 1 && horizConnections === 0) return 'COLUMN';

    // Horizontal span in one axis → beam
    if (horizX >= 2 && horizZ === 0 && vertConnections === 0) return 'BEAM';
    if (horizZ >= 2 && horizX === 0 && vertConnections === 0) return 'BEAM';
    if (horizX >= 1 && horizZ === 0 && vertConnections === 0) return 'BEAM';
    if (horizZ >= 1 && horizX === 0 && vertConnections === 0) return 'BEAM';

    // Horizontal plane without vertical → slab
    if (horizConnections >= 2 && vertConnections === 0) return 'SLAB';

    // Mixed connectivity: vertical dominant → column, else wall
    if (vertConnections > horizConnections) return 'COLUMN';
    if (horizConnections >= 2) return 'WALL';

    // Fall back to material default
    return matDef.defaultRole;
  });
}

// ─── IFC Entity Builders ──────────────────────────────────────────────────────

/**
 * Build the IFC spatial hierarchy and shared geometry resources.
 * Returns IDs of key shared entities needed for element creation.
 */
interface IfcContext {
  ownerHistoryId: number;
  placementOriginId: number;   // #id of IFCCARTESIANPOINT(0,0,0)
  axisZId: number;             // #id of IFCDIRECTION(0,0,1)
  axisXId: number;             // #id of IFCDIRECTION(1,0,0)
  worldPlacementId: number;    // #id of IFCAXIS2PLACEMENT3D at origin
  sitePlacementId: number;
  buildingPlacementId: number;
  storeyPlacementId: number;
  projectId: number;
  siteId: number;
  buildingId: number;
  storeyId: number;
  repContextId: number;        // IFCGEOMETRICREPRESENTATIONCONTEXT
  repSubContextId: number;     // IFCGEOMETRICREPRESENTATIONSUBCONTEXT (Body)
}

function buildSpatialHierarchy(w: IfcWriter, projectName: string): IfcContext {
  // Application + owner
  const personId = w.add('IFCPERSON', NULL, str('BlockReality'), str('System'), NULL, NULL, NULL, NULL, NULL);
  const orgId = w.add('IFCORGANIZATION', NULL, str('Anthropic Block Reality'), NULL, NULL, NULL);
  const paoId = w.add('IFCPERSONANDORGANIZATION', ref(personId), ref(orgId), NULL);
  const appId = w.add('IFCAPPLICATION', ref(orgId), str('1.0'), str('Block Reality Structural Exporter'), str('BlockReality'));
  const ownerHistoryId = w.add('IFCOWNERHISTORY',
    ref(paoId), ref(appId), NULL,
    enumVal('NOCHANGE'), NULL, NULL, NULL,
    int(Math.floor(Date.now() / 1000)),
  );

  // Units: SI (metres, kilograms, seconds, radians)
  const lenUnitId = w.add('IFCSIUNIT', NULL, enumVal('LENGTHUNIT'), NULL, enumVal('METRE'));
  const areaUnitId = w.add('IFCSIUNIT', NULL, enumVal('AREAUNIT'), NULL, enumVal('SQUARE_METRE'));
  const volUnitId = w.add('IFCSIUNIT', NULL, enumVal('VOLUMEUNIT'), NULL, enumVal('CUBIC_METRE'));
  const massUnitId = w.add('IFCSIUNIT', NULL, enumVal('MASSUNIT'), NULL, enumVal('KILOGRAM'));
  const pressurePrefix = w.add('IFCSIUNIT', NULL, enumVal('PRESSUREUNIT'), NULL, enumVal('PASCAL'));
  const radUnitId = w.add('IFCSIUNIT', NULL, enumVal('PLANEANGLEUNIT'), NULL, enumVal('RADIAN'));
  const timeUnitId = w.add('IFCSIUNIT', NULL, enumVal('TIMEUNIT'), NULL, enumVal('SECOND'));
  const unitAssignId = w.add('IFCUNITASSIGNMENT',
    refList([lenUnitId, areaUnitId, volUnitId, massUnitId, pressurePrefix, radUnitId, timeUnitId]),
  );

  // Shared geometry base points/directions
  const origin3d = w.add('IFCCARTESIANPOINT', `(${[real(0), real(0), real(0)].join(',')})`);
  const axisZ = w.add('IFCDIRECTION', `(${[real(0), real(0), real(1)].join(',')})`);
  const axisX = w.add('IFCDIRECTION', `(${[real(1), real(0), real(0)].join(',')})`);

  // World coordinate system
  const worldAxis2p3d = w.add('IFCAXIS2PLACEMENT3D', ref(origin3d), ref(axisZ), ref(axisX));
  const worldPlacementId = w.add('IFCLOCALPLACEMENT', NULL, ref(worldAxis2p3d));

  // Representation context
  const repContextId = w.add('IFCGEOMETRICREPRESENTATIONCONTEXT',
    NULL, str('Model'),
    int(3),          // coordinate space dimension
    real(1e-5),      // precision
    ref(worldAxis2p3d),
    NULL,
  );
  const repSubContextId = w.add('IFCGEOMETRICREPRESENTATIONSUBCONTEXT',
    str('Body'), str('Model'),
    NULL, NULL, NULL, NULL,
    ref(repContextId),
    NULL,
    enumVal('MODEL_VIEW'),
    NULL,
  );

  // Project
  const projectId = w.add('IFCPROJECT',
    str(ifcGuid()), ref(ownerHistoryId),
    str(projectName), NULL, NULL, NULL, NULL,
    refList([repContextId]),
    ref(unitAssignId),
  );

  // Site
  const sitePlacementId = w.add('IFCLOCALPLACEMENT', NULL, ref(worldAxis2p3d));
  const siteId = w.add('IFCSITE',
    str(ifcGuid()), ref(ownerHistoryId),
    str('Site'), NULL, NULL,
    ref(sitePlacementId), NULL,
    enumVal('ELEMENT'),
    NULL, NULL, NULL, NULL, NULL,
  );

  // Building
  const buildingAxis = w.add('IFCAXIS2PLACEMENT3D', ref(origin3d), ref(axisZ), ref(axisX));
  const buildingPlacementId = w.add('IFCLOCALPLACEMENT', ref(sitePlacementId), ref(buildingAxis));
  const buildingId = w.add('IFCBUILDING',
    str(ifcGuid()), ref(ownerHistoryId),
    str('Building'), NULL, NULL,
    ref(buildingPlacementId), NULL,
    enumVal('ELEMENT'),
    NULL, NULL, NULL,
  );

  // Storey (ground level = 0)
  const storeyAxis = w.add('IFCAXIS2PLACEMENT3D', ref(origin3d), ref(axisZ), ref(axisX));
  const storeyPlacementId = w.add('IFCLOCALPLACEMENT', ref(buildingPlacementId), ref(storeyAxis));
  const storeyId = w.add('IFCBUILDINGSTOREY',
    str(ifcGuid()), ref(ownerHistoryId),
    str('Ground Floor'), NULL, NULL,
    ref(storeyPlacementId), NULL,
    enumVal('ELEMENT'),
    NULL,
    real(0.0),  // elevation
  );

  // Spatial aggregation
  w.add('IFCRELAGGREGATES',
    str(ifcGuid()), ref(ownerHistoryId), NULL, NULL,
    ref(projectId), refList([siteId]),
  );
  w.add('IFCRELAGGREGATES',
    str(ifcGuid()), ref(ownerHistoryId), NULL, NULL,
    ref(siteId), refList([buildingId]),
  );
  w.add('IFCRELAGGREGATES',
    str(ifcGuid()), ref(ownerHistoryId), NULL, NULL,
    ref(buildingId), refList([storeyId]),
  );

  return {
    ownerHistoryId, placementOriginId: origin3d, axisZId: axisZ, axisXId: axisX,
    worldPlacementId, sitePlacementId, buildingPlacementId, storeyPlacementId,
    projectId, siteId, buildingId, storeyId,
    repContextId, repSubContextId,
  };
}

/** Build IFCMATERIAL + IFCMATERIALPROPERTIES for a material, return [materialId, propsId] */
function buildMaterial(w: IfcWriter, matDef: MaterialDef, ownerHistoryId: number): [number, number] {
  const matId = w.add('IFCMATERIAL', str(matDef.displayName), NULL, NULL);

  // IFC4 IfcMechanicalMaterialProperties (simplified via property set)
  // Using Pset_MaterialMechanical property set for max compatibility
  const ePropId = w.add('IFCPROPERTYSINGLEVALUE',
    str('YoungModulus'), NULL,
    typed('IFCMODULUSOFELASTICITYMEASURE', real(matDef.youngsModulus * 1e9)),  // GPa → Pa
    NULL,
  );
  const poissonPropId = w.add('IFCPROPERTYSINGLEVALUE',
    str('PoissonRatio'), NULL,
    typed('IFCPOSITIVERATIOMEASURE', real(0.2)),
    NULL,
  );
  const densityPropId = w.add('IFCPROPERTYSINGLEVALUE',
    str('MassDensity'), NULL,
    typed('IFCMASSDENSITYMEASURE', real(matDef.density)),
    NULL,
  );
  // Block Reality structural properties
  const rcompPropId = w.add('IFCPROPERTYSINGLEVALUE',
    str('CompressiveStrength'), NULL,
    typed('IFCPRESSUREMEASURE', real(matDef.rcomp * 1e6)),  // MPa → Pa
    NULL,
  );
  const rtensPropId = w.add('IFCPROPERTYSINGLEVALUE',
    str('TensileStrength'), NULL,
    typed('IFCPRESSUREMEASURE', real(matDef.rtens * 1e6)),
    NULL,
  );

  const matPsetId = w.add('IFCMATERIALPROPERTIES',
    str('Pset_MaterialMechanical'), NULL,
    refList([ePropId, poissonPropId, densityPropId, rcompPropId, rtensPropId]),
    ref(matId),
  );

  return [matId, matPsetId];
}

/** Build box geometry for a 1m×1m×1m block at position (x, y, z) */
function buildBlockGeometry(
  w: IfcWriter,
  x: number, y: number, z: number,
  blockSize: number,
  axisZId: number, axisXId: number,
  repSubContextId: number,
): number {
  // Placement point at block corner
  const placePt = w.add('IFCCARTESIANPOINT',
    `(${real(x * blockSize)},${real(y * blockSize)},${real(z * blockSize)})`,
  );
  const axis2p = w.add('IFCAXIS2PLACEMENT3D', ref(placePt), ref(axisZId), ref(axisXId));

  // 1m × 1m rectangle profile
  const profilePt = w.add('IFCCARTESIANPOINT', `(${real(0)},${real(0)})`);
  const profileAxis = w.add('IFCAXIS2PLACEMENT2D', ref(profilePt), NULL);
  const profileId = w.add('IFCRECTANGLEPROFILEDEF',
    enumVal('AREA'), NULL, ref(profileAxis),
    real(blockSize), real(blockSize),
  );

  // Extrude 1m in Z
  const solidId = w.add('IFCEXTRUDEDAREASOLID',
    ref(profileId), ref(axis2p),
    ref(axisZId),     // extrusion direction
    real(blockSize),  // depth
  );

  // Shape representation
  const shapeRepId = w.add('IFCSHAPEREPRESENTATION',
    ref(repSubContextId),
    str('Body'), str('SweptSolid'),
    refList([solidId]),
  );

  return shapeRepId;
}

/** Create the per-element structural property set */
function buildStructuralPset(
  w: IfcWriter,
  ownerHistoryId: number,
  stressLevel: number,
  isAnchored: boolean,
  elementId: number,
): void {
  // Utilization ratio = stress / capacity (simplified: stress directly)
  const utilPct = Math.min(100, stressLevel * 100);

  const stressPropId = w.add('IFCPROPERTYSINGLEVALUE',
    str('StressLevel'), str('Current stress level normalized 0.0-1.0'),
    typed('IFCNORMALISEDRATIOMEASURE', real(Math.min(1.0, Math.max(0.0, stressLevel)))),
    NULL,
  );
  const utilizationPropId = w.add('IFCPROPERTYSINGLEVALUE',
    str('UtilizationRatio'), str('Structural utilization in percent (%)'),
    typed('IFCPOSITIVERATIOMEASURE', real(utilPct / 100)),
    NULL,
  );
  const anchoredPropId = w.add('IFCPROPERTYSINGLEVALUE',
    str('IsAnchored'), str('Whether block is a fixed structural anchor'),
    typed('IFCBOOLEAN', isAnchored ? '.T.' : '.F.'),
    NULL,
  );

  const psetId = w.add('IFCPROPERTYSET',
    str(ifcGuid()), ref(ownerHistoryId),
    str('Pset_BlockReality_Structural'), NULL,
    refList([stressPropId, utilizationPropId, anchoredPropId]),
  );

  w.add('IFCRELDEFINESBYPROPERTIES',
    str(ifcGuid()), ref(ownerHistoryId), NULL, NULL,
    refList([elementId]),
    ref(psetId),
  );
}

/** Map StructuralRole to IFC type name */
function roleToIfcType(role: StructuralRole): string {
  switch (role) {
    case 'COLUMN':  return 'IFCCOLUMN';
    case 'BEAM':    return 'IFCBEAM';
    case 'WALL':    return 'IFCWALL';
    case 'SLAB':    return 'IFCSLAB';
    default:        return 'IFCBUILDINGELEMENT';
  }
}

/** Map StructuralRole to IFC element predefined type */
function roleToPredefinedType(role: StructuralRole): string {
  switch (role) {
    case 'COLUMN':  return 'COLUMN';
    case 'BEAM':    return 'BEAM';
    case 'WALL':    return 'NOTDEFINED';
    case 'SLAB':    return 'FLOOR';
    default:        return 'NOTDEFINED';
  }
}

// ─── Main Export Function ─────────────────────────────────────────────────────

/**
 * Convert BlueprintBlock[] to IFC 4 structural model and write to disk.
 *
 * @param blocks   Array of BlueprintBlock from Java SidecarBridge
 * @param options  Export options
 * @returns        Export statistics
 */
export async function exportToIfc4(
  blocks: BlueprintBlock[],
  options: Ifc4ExportOptions,
): Promise<Ifc4ExportResult> {
  const blockSize = options.blockSizeM ?? 1.0;
  const includeGeometry = options.includeGeometry !== false;  // default true
  const projectName = options.projectName ?? 'Block Reality Structure';

  const w = new IfcWriter();

  // 1. Build spatial hierarchy and shared context
  const ctx = buildSpatialHierarchy(w, projectName);

  // 2. Group blocks by material, build material entities
  const materialGroups = new Map<string, BlueprintBlock[]>();
  for (const block of blocks) {
    const matId = block.rMaterialId || 'default';
    if (!materialGroups.has(matId)) materialGroups.set(matId, []);
    materialGroups.get(matId)!.push(block);
  }

  // Material ID → [ifcMaterialId, ifcMaterialPropsId]
  const matEntityMap = new Map<string, [number, number]>();
  for (const [matId] of materialGroups) {
    const matDef = getMaterialDef(matId);
    const [mId, pId] = buildMaterial(w, matDef, ctx.ownerHistoryId);
    matEntityMap.set(matId, [mId, pId]);
  }

  // 3. Classify structural roles
  const roles = classifyBlocks(blocks);

  // 4. Build structural elements
  const elementIds: number[] = [];
  let columnCount = 0, beamCount = 0, wallCount = 0, slabCount = 0;
  let maxStressLevel = 0, maxUtilization = 0;

  // Collect elements per material for IFCRELASSOCIATESMATERIAL
  const matToElements = new Map<string, number[]>();

  for (let i = 0; i < blocks.length; i++) {
    const block = blocks[i]!;
    const role = roles[i]!;
    const matId = block.rMaterialId || 'default';
    const stressLevel = block.stressLevel ?? 0.0;
    const isAnchored = block.isAnchored ?? false;

    if (stressLevel > maxStressLevel) maxStressLevel = stressLevel;
    const utilPct = stressLevel * 100;
    if (utilPct > maxUtilization) maxUtilization = utilPct;

    // Count by role
    if (role === 'COLUMN') columnCount++;
    else if (role === 'BEAM') beamCount++;
    else if (role === 'WALL') wallCount++;
    else if (role === 'SLAB') slabCount++;

    // Element placement
    let placementId: number;
    if (includeGeometry) {
      const placePt = w.add('IFCCARTESIANPOINT',
        `(${real(block.relX * blockSize)},${real(block.relY * blockSize)},${real(block.relZ * blockSize)})`,
      );
      const axis2p = w.add('IFCAXIS2PLACEMENT3D', ref(placePt), ref(ctx.axisZId), ref(ctx.axisXId));
      placementId = w.add('IFCLOCALPLACEMENT', ref(ctx.storeyPlacementId), ref(axis2p));
    } else {
      placementId = ctx.storeyPlacementId;
    }

    // Shape representation (geometry)
    let productRepId: number | null = null;
    if (includeGeometry) {
      const shapeRepId = buildBlockGeometry(
        w,
        block.relX, block.relY, block.relZ,
        blockSize,
        ctx.axisZId, ctx.axisXId,
        ctx.repSubContextId,
      );
      // Note: geometry is in world coords, placement at origin
      const localPt = w.add('IFCCARTESIANPOINT', `(${real(0)},${real(0)},${real(0)})`);
      const localAxis = w.add('IFCAXIS2PLACEMENT3D', ref(localPt), ref(ctx.axisZId), ref(ctx.axisXId));
      const localPlacement = w.add('IFCLOCALPLACEMENT', NULL, ref(localAxis));

      const shapeRepBody = buildBlockGeometry(
        w, 0, 0, 0, blockSize, ctx.axisZId, ctx.axisXId, ctx.repSubContextId,
      );
      const prodDefShapeId = w.add('IFCPRODUCTDEFINITIONSHAPE', NULL, NULL, refList([shapeRepBody]));
      productRepId = prodDefShapeId;
    }

    // IFC element entity
    const ifcType = roleToIfcType(role);
    const predType = roleToPredefinedType(role);
    const elementId = w.add(ifcType,
      str(ifcGuid()),
      ref(ctx.ownerHistoryId),
      str(`${matId}_${role}_${i}`),   // Name
      str(block.blockState ?? NULL),   // Description = blockState
      NULL,                            // ObjectType
      ref(placementId),                // ObjectPlacement
      productRepId !== null ? ref(productRepId) : NULL,  // Representation
      NULL,                            // Tag
      enumVal(predType),               // PredefinedType
    );

    elementIds.push(elementId);

    // Track for material association
    if (!matToElements.has(matId)) matToElements.set(matId, []);
    matToElements.get(matId)!.push(elementId);

    // Per-element structural property set
    buildStructuralPset(w, ctx.ownerHistoryId, stressLevel, isAnchored, elementId);
  }

  // 5. Associate elements with spatial storey
  if (elementIds.length > 0) {
    w.add('IFCRELCONTAINEDINSPATIALSTRUCTURE',
      str(ifcGuid()), ref(ctx.ownerHistoryId),
      str('Elements in Ground Floor'), NULL,
      refList(elementIds),
      ref(ctx.storeyId),
    );
  }

  // 6. Associate elements with materials
  for (const [matId, elIds] of matToElements) {
    const [ifcMatId] = matEntityMap.get(matId)!;
    const matSelectId = w.add('IFCMATERIALLIST', refList([ifcMatId]));
    w.add('IFCRELASSOCIATESMATERIAL',
      str(ifcGuid()), ref(ctx.ownerHistoryId), NULL, NULL,
      refList(elIds),
      ref(ifcMatId),
    );
  }

  // 7. Build structural analysis model (for FEA import compatibility)
  const saModelId = w.add('IFCSTRUCTURALANALYSISMODEL',
    str(ifcGuid()), ref(ctx.ownerHistoryId),
    str('Block Reality Structural Model'), NULL, NULL,
    enumVal('NOTDEFINED'),
    NULL, NULL, NULL,
    ref(ctx.worldPlacementId),
  );
  w.add('IFCRELAGGREGATES',
    str(ifcGuid()), ref(ctx.ownerHistoryId), NULL, NULL,
    ref(ctx.projectId), refList([saModelId]),
  );

  // 8. Write file
  const filename = options.outputPath.split(/[/\\]/).pop() ?? 'export.ifc';
  const content = w.build(filename, options.authorOrg ?? 'Block Reality');
  fs.writeFileSync(options.outputPath, content, 'utf8');

  return {
    success: true,
    outputPath: options.outputPath,
    blockCount: blocks.length,
    elementCount: elementIds.length,
    materialCount: matEntityMap.size,
    columnCount, beamCount, wallCount, slabCount,
    maxStressLevel,
    maxUtilization,
  };
}
