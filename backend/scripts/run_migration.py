import asyncio
import os
from pathlib import Path
from dotenv import load_dotenv
from supabase import create_client

load_dotenv()

async def run_migration():
    url = os.getenv("SUPABASE_URL")
    key = os.getenv("SUPABASE_KEY")

    if not url or not key:
        print("ERROR: SUPABASE_URL or SUPABASE_KEY not set in .env")
        return

    client = create_client(url, key)

    migration_path = Path(__file__).parent.parent / "supabase" / "migrations" / "001_initial_schema.sql"
    sql = migration_path.read_text()

    print("Running migration 001_initial_schema.sql...")

    try:
        client.rpc("exec_sql", {"sql": sql}).execute()
        print("Migration complete.")
    except Exception as e:
        print(f"Note: {e}")
        print("Please run the SQL manually in Supabase Dashboard > SQL Editor.")
        print(f"File: {migration_path}")

asyncio.run(run_migration())
