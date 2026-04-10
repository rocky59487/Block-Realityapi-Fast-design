import { writeFileSync } from 'node:fs';
import { resolve, normalize } from 'node:path';
import { getOC } from './mesh-to-brep.js';

const VIRTUAL_TMP = '/tmp/_mctonurbs_output.step';

/**
 * Export an OpenCASCADE shape to a STEP file.
 *
 * Because opencascade.js (Emscripten) writes to its virtual filesystem,
 * we write to a virtual path first, then copy the content to the real
 * filesystem via Node.js fs.
 *
 * @param shape - A TopoDS_Shape (solid, shell, or compound)
 * @param filePath - Output file path for the .step file (real filesystem)
 * @throws Error if filePath attempts path traversal outside the allowed directory
 */
export function writeSTEP(shape: any /* TopoDS_Shape */, filePath: string): void {
  // ★ Security fix: validate output path is within allowed export directory
  const allowedDir = resolve(process.cwd(), 'exports');
  const canonicalPath = resolve(filePath);

  if (!canonicalPath.startsWith(allowedDir)) {
    throw new Error(
      `Path traversal blocked: output path must be within ${allowedDir}, got ${canonicalPath}`
    );
  }

  const content = shapeToSTEPString(shape);
  writeFileSync(canonicalPath, content, 'utf-8');
}

/**
 * Export an OpenCASCADE shape to a STEP file as a string (in-memory).
 * Useful for IPC where we want to return the STEP content directly.
 */
export function shapeToSTEPString(shape: any /* TopoDS_Shape */): string {
  const oc = getOC();

  const writer = new oc.STEPControl_Writer_1();
  try {
    const progress = new oc.Message_ProgressRange_1();
    const transferStatus = writer.Transfer(
      shape,
      oc.STEPControl_StepModelType.STEPControl_AsIs,
      true,
      progress,
    );
    progress.delete();

    // Write to Emscripten virtual FS
    writer.Write(VIRTUAL_TMP);
  } finally {
    writer.delete();
  }

  // Read from Emscripten virtual FS and clean up
  const content = oc.FS.readFile(VIRTUAL_TMP, { encoding: 'utf8' });
  oc.FS.unlink(VIRTUAL_TMP);

  return content;
}

/**
 * Create a compound shape from multiple shapes (one per material).
 * This allows exporting multiple material groups as a single STEP assembly.
 */
export function makeCompound(shapes: any[]): any /* TopoDS_Compound */ {
  const oc = getOC();

  const compound = new oc.TopoDS_Compound();
  const builder = new oc.BRep_Builder();
  builder.MakeCompound(compound);

  for (const shape of shapes) {
    builder.Add(compound, shape);
  }

  builder.delete();
  return compound;
}
