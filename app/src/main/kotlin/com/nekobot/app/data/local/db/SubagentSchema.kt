package com.nekobot.app.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** Upstream v0.8.3 subagent tables, applied after the personal memory migrations. */
internal object SubagentSchema {
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_subagent_tasks (
                task_id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                root_run_id TEXT NOT NULL,
                parent_task_id TEXT,
                parent_message_id TEXT,
                parent_tool_call_id TEXT,
                request_key TEXT,
                description TEXT NOT NULL,
                prompt TEXT NOT NULL,
                run_in_background INTEGER NOT NULL,
                depth INTEGER NOT NULL,
                status TEXT NOT NULL,
                stage TEXT NOT NULL,
                execution_generation INTEGER NOT NULL,
                completed_tool_calls INTEGER NOT NULL,
                checkpoint_sequence INTEGER NOT NULL,
                todos_json TEXT NOT NULL,
                result_text TEXT NOT NULL,
                error_text TEXT,
                model_used TEXT NOT NULL,
                tool_calls INTEGER NOT NULL,
                steps_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                started_at INTEGER,
                finished_at INTEGER,
                updated_at INTEGER NOT NULL,
                source_device_id TEXT,
                PRIMARY KEY(task_id),
                FOREIGN KEY(session_id) REFERENCES local_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_subagent_tasks_session_id ON local_subagent_tasks(session_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_subagent_tasks_parent_task_id ON local_subagent_tasks(parent_task_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_local_subagent_tasks_request_key ON local_subagent_tasks(request_key)")
        db.execSQL(
            "ALTER TABLE local_subagent_tasks ADD COLUMN tool_call_states_json TEXT NOT NULL DEFAULT '{}'"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_subagent_messages (
                task_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                payload_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(task_id, sequence),
                FOREIGN KEY(task_id) REFERENCES local_subagent_tasks(task_id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_local_subagent_messages_task_id ON local_subagent_messages(task_id)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_subagent_tool_calls (
                task_id TEXT NOT NULL,
                execution_generation INTEGER NOT NULL,
                call_id TEXT NOT NULL,
                assistant_sequence INTEGER NOT NULL,
                result_sequence INTEGER,
                tool_name TEXT NOT NULL,
                arguments_summary TEXT NOT NULL,
                status TEXT NOT NULL,
                started_at INTEGER,
                finished_at INTEGER,
                recovery_decision TEXT,
                PRIMARY KEY(task_id, execution_generation, call_id),
                FOREIGN KEY(task_id) REFERENCES local_subagent_tasks(task_id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_subagent_tool_calls_task_id ON local_subagent_tool_calls(task_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_subagent_tool_calls_task_id_execution_generation_status ON local_subagent_tool_calls(task_id, execution_generation, status)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_agent_notices (
                notice_id TEXT NOT NULL PRIMARY KEY,
                task_id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                parent_task_id TEXT,
                execution_generation INTEGER NOT NULL,
                payload TEXT NOT NULL,
                delivery_state TEXT NOT NULL DEFAULT 'pending',
                created_at INTEGER NOT NULL,
                delivered_at INTEGER,
                consumed_by_run_id TEXT,
                FOREIGN KEY(task_id) REFERENCES local_subagent_tasks(task_id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(parent_task_id) REFERENCES local_subagent_tasks(task_id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_agent_notices_task_id ON local_agent_notices(task_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_agent_notices_session_id_delivery_state_created_at ON local_agent_notices(session_id, delivery_state, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_agent_notices_parent_task_id_delivery_state ON local_agent_notices(parent_task_id, delivery_state)")
        db.execSQL("ALTER TABLE local_subagent_tasks ADD COLUMN source_task_id TEXT")
    }
}
