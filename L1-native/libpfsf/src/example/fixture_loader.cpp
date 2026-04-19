#include "fixture_loader.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>

namespace pfsf_cli {

// ───────────────────────── base64 decode ─────────────────────────

namespace {

int b64_rev(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return 26 + (c - 'a');
    if (c >= '0' && c <= '9') return 52 + (c - '0');
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;  /* '=' padding or invalid */
}

bool decode_base64(const std::string& in, std::vector<uint8_t>& out) {
    out.clear();
    out.reserve((in.size() * 3) / 4);
    int buf = 0, bits = 0;
    for (char c : in) {
        if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;
        if (c == '=') break;
        int v = b64_rev(c);
        if (v < 0) return false;
        buf = (buf << 6) | v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back(static_cast<uint8_t>((buf >> bits) & 0xFF));
        }
    }
    return true;
}

// ─────────────── minimal JSON parser (schema-subset) ─────────────
//
// Supports: objects, arrays, strings (no unicode escapes beyond \" \\
// \n \r \t \/), numbers (int + decimal + optional leading -), true,
// false, null. Enough for the fixture schema; intentionally rejects
// anything richer so pathological input fails loud.

struct Json;
using JsonObj = std::vector<std::pair<std::string, Json>>;
using JsonArr = std::vector<Json>;

struct Json {
    enum Kind { NUL, BOOL, NUM, STR, ARR, OBJ } kind = NUL;
    bool        b = false;
    double      n = 0.0;
    std::string s;
    JsonArr     a;
    JsonObj     o;
};

struct JsonParser {
    const std::string& src;
    size_t             pos = 0;
    std::string        err;

    explicit JsonParser(const std::string& text) : src(text) {}

    void skip_ws() {
        while (pos < src.size()) {
            char c = src[pos];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') { ++pos; continue; }
            break;
        }
    }

    bool fail(const std::string& msg) {
        err = "json parse error at byte " + std::to_string(pos) + ": " + msg;
        return false;
    }

    bool expect(char c) {
        if (pos >= src.size() || src[pos] != c) return fail(std::string("expected '") + c + "'");
        ++pos;
        return true;
    }

    bool parse_string(std::string& out) {
        if (!expect('"')) return false;
        out.clear();
        while (pos < src.size()) {
            char c = src[pos++];
            if (c == '"') return true;
            if (c == '\\') {
                if (pos >= src.size()) return fail("truncated escape");
                char e = src[pos++];
                switch (e) {
                    case '"':  out.push_back('"');  break;
                    case '\\': out.push_back('\\'); break;
                    case '/':  out.push_back('/');  break;
                    case 'n':  out.push_back('\n'); break;
                    case 'r':  out.push_back('\r'); break;
                    case 't':  out.push_back('\t'); break;
                    case 'b':  out.push_back('\b'); break;
                    case 'f':  out.push_back('\f'); break;
                    default:   return fail(std::string("unsupported escape \\") + e);
                }
            } else {
                out.push_back(c);
            }
        }
        return fail("unterminated string");
    }

    bool parse_number(Json& j) {
        size_t start = pos;
        if (src[pos] == '-') ++pos;
        while (pos < src.size() && ((src[pos] >= '0' && src[pos] <= '9') ||
                                    src[pos] == '.' || src[pos] == 'e' ||
                                    src[pos] == 'E' || src[pos] == '+' ||
                                    src[pos] == '-')) {
            ++pos;
        }
        std::string tok = src.substr(start, pos - start);
        char* end = nullptr;
        j.kind = Json::NUM;
        j.n    = std::strtod(tok.c_str(), &end);
        /* PR#187 capy-ai R23: strtod only tells us whether it parsed
         * anything, not whether it consumed the whole token. The lexer
         * permits '+' and '-' anywhere in the span, so malformed numerics
         * like "1-2" or "1e" would previously be accepted as 1 and the
         * trailing characters silently discarded. Require full-token
         * consumption so corrupted fixture JSON fails fast rather than
         * driving the CLI against partial data. */
        if (end == tok.c_str() || *end != '\0') return fail("bad number: " + tok);
        return true;
    }

    bool parse_literal(const char* lit, Json& j, Json::Kind kind) {
        size_t n = std::strlen(lit);
        if (pos + n > src.size() || std::memcmp(src.data() + pos, lit, n) != 0) {
            return fail(std::string("expected ") + lit);
        }
        pos += n;
        j.kind = kind;
        if (kind == Json::BOOL) j.b = (lit[0] == 't');
        return true;
    }

    bool parse_value(Json& out) {
        skip_ws();
        if (pos >= src.size()) return fail("unexpected end");
        char c = src[pos];
        switch (c) {
            case '{': return parse_object(out);
            case '[': return parse_array(out);
            case '"': out.kind = Json::STR; return parse_string(out.s);
            case 't': return parse_literal("true",  out, Json::BOOL);
            case 'f': return parse_literal("false", out, Json::BOOL);
            case 'n': return parse_literal("null",  out, Json::NUL);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return parse_number(out);
                return fail(std::string("unexpected char '") + c + "'");
        }
    }

    bool parse_array(Json& out) {
        if (!expect('[')) return false;
        out.kind = Json::ARR;
        skip_ws();
        if (pos < src.size() && src[pos] == ']') { ++pos; return true; }
        while (true) {
            Json v;
            if (!parse_value(v)) return false;
            out.a.push_back(std::move(v));
            skip_ws();
            if (pos < src.size() && src[pos] == ',') { ++pos; skip_ws(); continue; }
            if (!expect(']')) return false;
            return true;
        }
    }

    bool parse_object(Json& out) {
        if (!expect('{')) return false;
        out.kind = Json::OBJ;
        skip_ws();
        if (pos < src.size() && src[pos] == '}') { ++pos; return true; }
        while (true) {
            skip_ws();
            std::string key;
            if (!parse_string(key)) return false;
            skip_ws();
            if (!expect(':')) return false;
            Json v;
            if (!parse_value(v)) return false;
            out.o.emplace_back(std::move(key), std::move(v));
            skip_ws();
            if (pos < src.size() && src[pos] == ',') { ++pos; continue; }
            if (!expect('}')) return false;
            return true;
        }
    }
};

const Json* find(const JsonObj& obj, const char* key) {
    for (auto& kv : obj) if (kv.first == key) return &kv.second;
    return nullptr;
}

bool as_int(const Json& j, int32_t& out) {
    if (j.kind != Json::NUM) return false;
    if (j.n < INT32_MIN || j.n > INT32_MAX) return false;
    // PR#187 capy-ai R58: reject non-integral fixture tokens. Schema fields
    // like lx/ly/lz, anchor coords, tick counts, and material ids are
    // documented as integers; silently truncating `"lx": 3.5` to 3 hides
    // real schema drift and replays a different fixture than the JSON
    // described. std::trunc preserves the sign so `-1.9` also fails.
    double trunc = std::trunc(j.n);
    if (trunc != j.n || !std::isfinite(j.n)) return false;
    out = static_cast<int32_t>(trunc);
    return true;
}
bool as_float(const Json& j, float& out) {
    if (j.kind != Json::NUM) return false;
    out = static_cast<float>(j.n);
    return true;
}
bool as_str(const Json& j, std::string& out) {
    if (j.kind != Json::STR) return false;
    out = j.s;
    return true;
}

/** Decode a base64 field into a typed vector (typed as float32 or int32). */
template <typename T>
bool decode_field(const Json* field, std::vector<T>& out) {
    if (field == nullptr || field->kind == Json::NUL) { out.clear(); return true; }
    if (field->kind != Json::STR) return false;
    std::vector<uint8_t> bytes;
    if (!decode_base64(field->s, bytes)) return false;
    if (bytes.size() % sizeof(T) != 0) return false;
    out.resize(bytes.size() / sizeof(T));
    if (!bytes.empty()) std::memcpy(out.data(), bytes.data(), bytes.size());
    return true;
}

} /* namespace */

// ───────────────────────── public API ────────────────────────────

const FixtureMaterialEntry& lookup_material(const Fixture& fx, int32_t id) {
    for (auto& m : fx.material_registry) if (m.id == id) return m;
    return fx.material_registry.front();
}

LoadResult load_fixture(const std::string& path) {
    std::ifstream f(path);
    if (!f) {
        LoadResult bad;
        bad.error = "cannot open fixture: " + path;
        return bad;
    }
    std::stringstream ss;
    ss << f.rdbuf();
    return parse_fixture(ss.str());
}

LoadResult parse_fixture(const std::string& json) {
    LoadResult res;
    JsonParser jp(json);
    Json root;
    if (!jp.parse_value(root)) {
        res.error = jp.err;
        return res;
    }
    // PR#187 capy-ai R60: after consuming the first JSON value, only
    // trailing whitespace is legal. Accepting `<valid fixture>}BROKEN`
    // would replay partial data while claiming ok=true; the header
    // comment promises broken JSON surfaces as {ok=false, error=...}.
    jp.skip_ws();
    if (jp.pos != jp.src.size()) {
        res.error = "json parse error at byte " + std::to_string(jp.pos) +
                    ": unexpected trailing content after root value";
        return res;
    }
    if (root.kind != Json::OBJ) {
        res.error = "fixture root must be an object";
        return res;
    }
    Fixture& fx = res.fixture;

    if (const Json* j = find(root.o, "schema_version"))  as_int(*j, fx.schema_version);
    if (fx.schema_version != 1) {
        res.error = "unsupported schema_version: " +
                    std::to_string(fx.schema_version) + " (expected 1)";
        return res;
    }

    if (const Json* j = find(root.o, "fixture_id"))   as_str(*j, fx.fixture_id);
    if (const Json* j = find(root.o, "description")) as_str(*j, fx.description);
    if (const Json* j = find(root.o, "recorded_at")) as_str(*j, fx.recorded_at);
    if (const Json* j = find(root.o, "git_sha"))     as_str(*j, fx.git_sha);

    const Json* dims = find(root.o, "dims");
    if (dims == nullptr || dims->kind != Json::OBJ) {
        res.error = "missing/invalid dims object";
        return res;
    }
    if (const Json* j = find(dims->o, "lx")) as_int(*j, fx.lx);
    if (const Json* j = find(dims->o, "ly")) as_int(*j, fx.ly);
    if (const Json* j = find(dims->o, "lz")) as_int(*j, fx.lz);
    if (fx.lx <= 0 || fx.ly <= 0 || fx.lz <= 0) {
        res.error = "invalid dims: lx=" + std::to_string(fx.lx) +
                    " ly=" + std::to_string(fx.ly) +
                    " lz=" + std::to_string(fx.lz);
        return res;
    }
    const size_t N = static_cast<size_t>(fx.lx) *
                     static_cast<size_t>(fx.ly) *
                     static_cast<size_t>(fx.lz);

    /* anchors: array of [x,y,z] int triples. */
    if (const Json* ja = find(root.o, "anchors")) {
        if (ja->kind == Json::ARR) {
            fx.anchors.reserve(ja->a.size());
            for (const Json& e : ja->a) {
                if (e.kind != Json::ARR || e.a.size() != 3) {
                    res.error = "anchors entry must be [x,y,z]";
                    return res;
                }
                std::array<int32_t, 3> p{};
                if (!as_int(e.a[0], p[0]) || !as_int(e.a[1], p[1]) || !as_int(e.a[2], p[2])) {
                    res.error = "anchors entry must be int triple";
                    return res;
                }
                fx.anchors.push_back(p);
            }
        }
    }

    /* materials: {voxels: base64 int32[N], registry: [{id,name,rcomp,rtens,...}]} */
    const Json* mats = find(root.o, "materials");
    if (mats == nullptr || mats->kind != Json::OBJ) {
        res.error = "missing materials object";
        return res;
    }
    if (!decode_field<int32_t>(find(mats->o, "voxels"), fx.material_voxels)) {
        res.error = "invalid materials.voxels base64/int32 payload";
        return res;
    }
    if (fx.material_voxels.empty()) {
        fx.material_voxels.assign(N, 1);  /* default = id 1 everywhere */
    } else if (fx.material_voxels.size() != N) {
        res.error = "materials.voxels length " +
                    std::to_string(fx.material_voxels.size()) +
                    " != N=" + std::to_string(N);
        return res;
    }
    if (const Json* reg = find(mats->o, "registry"); reg && reg->kind == Json::ARR) {
        for (const Json& ent : reg->a) {
            if (ent.kind != Json::OBJ) continue;
            FixtureMaterialEntry m;
            if (const Json* j = find(ent.o, "id"))          as_int(*j,  m.id);
            if (const Json* j = find(ent.o, "name"))       as_str(*j,  m.name);
            if (const Json* j = find(ent.o, "rcomp"))      as_float(*j, m.rcomp);
            if (const Json* j = find(ent.o, "rtens"))      as_float(*j, m.rtens);
            if (const Json* j = find(ent.o, "density"))    as_float(*j, m.density);
            if (const Json* j = find(ent.o, "youngs_gpa")) as_float(*j, m.youngs_gpa);
            if (const Json* j = find(ent.o, "poisson"))    as_float(*j, m.poisson);
            if (const Json* j = find(ent.o, "gc"))         as_float(*j, m.gc);
            if (const Json* j = find(ent.o, "is_anchor"))
                m.is_anchor = (j->kind == Json::BOOL && j->b);
            fx.material_registry.push_back(std::move(m));
        }
    }
    if (fx.material_registry.empty()) {
        FixtureMaterialEntry fallback;
        fallback.id    = 1;
        fallback.name  = "concrete_c30";
        fallback.rcomp = 30.0f;
        fallback.rtens = 3.0f;
        fx.material_registry.push_back(fallback);
    }

    /* Optional float32[N] arrays. */
    if (!decode_field<float>(find(root.o, "fluid_pressure"), fx.fluid_pressure)) {
        res.error = "invalid fluid_pressure payload";
        return res;
    }
    if (!fx.fluid_pressure.empty() && fx.fluid_pressure.size() != N) {
        res.error = "fluid_pressure length mismatch";
        return res;
    }
    if (!decode_field<float>(find(root.o, "curing"), fx.curing)) {
        res.error = "invalid curing payload";
        return res;
    }
    if (!fx.curing.empty() && fx.curing.size() != N) {
        res.error = "curing length mismatch";
        return res;
    }
    if (!decode_field<float>(find(root.o, "expected_stress"), fx.expected_stress)) {
        res.error = "invalid expected_stress payload";
        return res;
    }
    if (!fx.expected_stress.empty() && fx.expected_stress.size() != N) {
        res.error = "expected_stress length mismatch";
        return res;
    }

    /* wind: [x, y, z] floats (optional). */
    if (const Json* jw = find(root.o, "wind"); jw && jw->kind == Json::ARR && jw->a.size() == 3) {
        as_float(jw->a[0], fx.wind[0]);
        as_float(jw->a[1], fx.wind[1]);
        as_float(jw->a[2], fx.wind[2]);
    }

    if (const Json* j = find(root.o, "ticks")) as_int(*j, fx.ticks);
    if (fx.ticks <= 0) fx.ticks = 1;

    if (const Json* jf = find(root.o, "expected_failures"); jf && jf->kind == Json::ARR) {
        for (const Json& e : jf->a) {
            if (e.kind != Json::OBJ) continue;
            FixtureFailure ff;
            if (const Json* jp = find(e.o, "pos"); jp && jp->kind == Json::ARR && jp->a.size() == 3) {
                as_int(jp->a[0], ff.pos[0]);
                as_int(jp->a[1], ff.pos[1]);
                as_int(jp->a[2], ff.pos[2]);
            }
            if (const Json* j = find(e.o, "type")) as_str(*j, ff.type);
            if (const Json* j = find(e.o, "tick")) as_int(*j, ff.tick);
            fx.expected_failures.push_back(std::move(ff));
        }
    }

    if (const Json* tol = find(root.o, "tolerances"); tol && tol->kind == Json::OBJ) {
        if (const Json* j = find(tol->o, "stress_abs"))   as_float(*j, fx.tol_stress_abs);
        if (const Json* j = find(tol->o, "failure_tick")) as_int(*j,  fx.tol_failure_tick);
    }

    res.ok = true;
    return res;
}

} /* namespace pfsf_cli */
