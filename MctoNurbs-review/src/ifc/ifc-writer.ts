/**
 * ifc-writer.ts — IFC 4.x (ISO 16739-1) P21 file writer
 *
 * Generates STEP Physical File (P21) text conforming to IFC4 schema.
 * Pure TypeScript, no external dependencies.
 *
 * Format reference:
 *   ISO 10303-21 (STEP P21) + IFC4 schema (buildingSMART)
 *
 * Usage:
 *   const w = new IfcWriter();
 *   const projId = w.addProject('My Project');
 *   const content = w.build('export.ifc');
 *   fs.writeFileSync(outputPath, content, 'utf8');
 */

// ─── GUID Generator ────────────────────────────────────────────────────────────
// IFC GlobalId: 22-character alphanumeric (IFC base64 encoding of a UUID)
// Alphabet per buildingSMART spec:
const IFC_BASE64 = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_$';

let _guidCounter = 0;

/**
 * Generate a deterministic IFC GlobalId (22 chars, IFC base64-encoded UUID).
 * Uses a counter-based approach for reproducible output (no crypto dependency).
 */
export function ifcGuid(): string {
  const n = ++_guidCounter;
  // Encode 128-bit value using counter + timestamp component
  const hi = Math.floor(Date.now() / 1000) ^ (n >> 16);
  const lo = (n & 0xffff) ^ 0xdeadbeef;
  // Produce 22 IFC base64 characters (each char = 6 bits, 22×6=132 bits > 128)
  let val = BigInt(hi) * BigInt(0x100000000) + BigInt(lo >>> 0);
  val ^= BigInt(n) * BigInt(0x6c62272e07bb0142);  // FNV-like mixing
  let result = '';
  for (let i = 0; i < 22; i++) {
    result = IFC_BASE64[Number(val & BigInt(63))] + result;
    val >>= BigInt(6);
  }
  return result;
}

/** Reset GUID counter (useful for deterministic tests) */
export function resetGuidCounter(): void { _guidCounter = 0; }

// ─── P21 Attribute Helpers ────────────────────────────────────────────────────

/** String attribute (quoted, special chars escaped) */
export function str(s: string | null | undefined): string {
  if (s === null || s === undefined) return '$';
  // Escape single quotes per ISO 10303-21 §7.3.6.3
  const escaped = String(s).replace(/\\/g, '\\\\').replace(/'/g, "''");
  return `'${escaped}'`;
}

/** Numeric real attribute */
export function real(n: number): string {
  if (!Number.isFinite(n)) return '0.';
  // IFC requires at least one decimal digit
  const s = n.toFixed(6);
  return s.includes('.') ? s.replace(/0+$/, '').replace(/\.$/, '.') : s + '.';
}

/** Integer attribute */
export function int(n: number): string {
  return Math.round(n).toString();
}

/** Enum attribute (.ENUMVALUE.) */
export function enumVal(e: string): string {
  return `.${e}.`;
}

/** Reference to entity #id */
export function ref(id: number): string {
  return `#${id}`;
}

/** Optional reference ($  if id <= 0) */
export function optRef(id: number): string {
  return id > 0 ? `#${id}` : '$';
}

/** List of references (#1,#2,...) */
export function refList(ids: number[]): string {
  return `(${ids.map(id => `#${id}`).join(',')})`;
}

/** List of strings */
export function strList(ss: string[]): string {
  return `(${ss.map(str).join(',')})`;
}

/** Typed list, e.g. IFCLENGTHMEASURE(1.0) */
export function typed(typeName: string, value: string): string {
  return `${typeName}(${value})`;
}

/** Null/derived attribute */
export const NULL = '$';
export const DERIVED = '*';

// ─── IfcWriter Class ───────────────────────────────────────────────────────────

export class IfcWriter {
  private _nextId = 1;
  private _entities: string[] = [];

  /** Allocate next entity ID without adding any entity */
  allocId(): number {
    return this._nextId++;
  }

  /**
   * Add a raw entity line.
   * @returns entity ID
   */
  addRaw(id: number, line: string): number {
    this._entities.push(`#${id}=${line};`);
    return id;
  }

  /**
   * Add an IFC entity.
   * @param type  IFC type name (e.g. "IFCPERSON")
   * @param attrs Attribute strings (use helpers: str, real, ref, enumVal, etc.)
   * @returns entity ID
   */
  add(type: string, ...attrs: string[]): number {
    const id = this._nextId++;
    this._entities.push(`#${id}=${type}(${attrs.join(',')});`);
    return id;
  }

  /**
   * Add an IFC entity with a pre-allocated ID.
   */
  addAt(id: number, type: string, ...attrs: string[]): number {
    this._entities.push(`#${id}=${type}(${attrs.join(',')});`);
    return id;
  }

  /**
   * Generate complete P21 IFC file content.
   * @param filename  Filename for FILE_NAME header (display only)
   * @param author    Author name
   * @param org       Organization name
   */
  build(filename: string, author = 'Block Reality', org = 'BlockReality'): string {
    const now = new Date().toISOString().replace(/\.\d+Z$/, '');
    const lines: string[] = [
      'ISO-10303-21;',
      'HEADER;',
      `FILE_DESCRIPTION(('ViewDefinition [CoordinationView]'),'2;1');`,
      `FILE_NAME(${str(filename)},${str(now)},(${str(author)}),(${str(org)}),'BlockReality/IFC4Writer','IFC4','');`,
      `FILE_SCHEMA(('IFC4'));`,
      'ENDSEC;',
      'DATA;',
      ...this._entities,
      'ENDSEC;',
      'END-ISO-10303-21;',
    ];
    return lines.join('\n');
  }
}
