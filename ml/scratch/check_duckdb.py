import duckdb
try:
    from brnext.data import DatasetRegistry
    print("DUCKDB_OK")
except ImportError as e:
    print(f"IMPORT_ERR: {e}")
