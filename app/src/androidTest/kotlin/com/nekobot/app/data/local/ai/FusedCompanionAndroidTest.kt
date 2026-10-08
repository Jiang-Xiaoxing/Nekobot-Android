package com.nekobot.app.data.local.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class FusedCompanionAndroidTest {
    @Test fun rankedRecallRejectsBrokenEvidenceAndFindsNextValidCandidate() = runBlocking<Unit> {
        val db=db(); try {
            val session=setup(db)
            val store=CompanionMemoryStore(db)
            val scope=store.ensureScope(session)
            val source=say(db,"tea-source","我喝茶不加糖")
            val dao=db.companionMemoryDao()
            fun fact(id:String,content:String)=CompanionMemoryEntity(id,scope.id,id,1,"fact","用户",content,"active",
                createdAt="now",updatedAt="now")
            dao.saveMemory(fact("bad","喝茶喝茶喝茶加糖"))
            dao.saveMemory(fact("good","喝茶不加糖"))
            dao.saveSources(listOf(
                CompanionSourceEntity("bad",source.id,"bad fingerprint","我喝茶",null,"user"),
                CompanionSourceEntity("good",source.id,CompanionMemoryPolicy.fingerprint(source),"我喝茶",null,"user")))
            val result=store.fusedRecall(session,"喝茶",FusedCompanionPolicy.budgets(14000)).first
            val ids=JsonParser.parseString(result.substringAfter('\n')).asJsonArray.map { it.asJsonObject["id"].asString }
            assertFalse("Invalid high-ranking evidence must never reach the model", "bad" in ids)
            assertTrue("The next valid fact must still be considered", "good" in ids)
            db.messageDao().upsert(source.copy(content="我现在改成喝白水"))
            assertFalse(store.fusedRecall(session,"喝茶",FusedCompanionPolicy.budgets(14000)).first.contains("不加糖"))
        } finally { db.close() }
    }
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val gson = Gson()
    private fun db() = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
    private suspend fun setup(db: NekobotDatabase): LocalSessionEntity {
        FusedCompanionSchema.installTriggers(db.openHelper.writableDatabase)
        db.characterDao().upsert(LocalCharacterEntity("c", "测试角色", createdAt="now", updatedAt="now"))
        return LocalSessionEntity("s","测试窗口",characterId="c",sessionMode="agent",inheritCharacter=true,
            longConversationEnabled=true,companionMode=true,createdAt="now",updatedAt="now").also {
            db.sessionDao().upsert(it); CompanionMemoryStore(db).ensureScope(it)
        }
    }
    private suspend fun say(db: NekobotDatabase, id: String, content: String, role: String="user") =
        LocalMessageEntity(id,"s",role,content,timestamp="2026-10-08T12:00:00Z",createdAt="2026-10-08T12:00:00Z").also { db.messageDao().upsert(it) }
    private suspend fun backlog(db: NekobotDatabase): List<LocalMessageEntity> {
        val rows = mutableListOf<LocalMessageEntity>()
        var n=0
        while (FusedCompanionPolicy.tokens(rows) < 12_000) {
            rows += say(db,"u$n",if(n==0) "我怕辣。"+"旧场景细节".repeat(200) else "近期参考资料".repeat(200))
            rows += say(db,"a$n","一起聊到这里。","assistant"); n++
        }
        return rows
    }
    private fun response(source: LocalMessageEntity, key: String="diet", operation: String="add", content: String="用户怕辣") =
        gson.toJson(mapOf("changes" to listOf(mapOf("key" to key,"target_key" to if(operation=="add") "" else key,
            "operation" to operation,"kind" to "fact","category" to "user_profile","subject" to "用户",
            "content" to content,"source_id" to source.id,"quote" to source.content.take(20)))))
    private suspend fun fails(block: suspend () -> Unit) { try { block(); fail("Expected rejection") } catch (e: Exception) { assertTrue(e.message != null) } }

    @Test fun modeOwnershipRejectsSecondWindowRebindingAndDowngradeAtSqlLayer() = runBlocking<Unit> {
        val db=db(); try {
            val s=setup(db)
            fails { db.sessionDao().upsert(s.copy(id="second")) }
            fails { db.sessionDao().update(s.copy(characterId="other")) }
            fails { db.sessionDao().update(s.copy(companionMode=false)) }
            fails { db.sessionDao().update(s.copy(longConversationEnabled=false)) }
            fails { db.sessionDao().upsert(s.copy(id="ordinary",companionMode=false,longConversationEnabled=false)) }
            fails { db.sessionDao().upsert(s.copy(id="group",characterId=null,characterIds="[\"c\"]",sessionMode="group",companionMode=false)) }
            assertEquals("s",db.companionMemoryDao().owner("c")?.sessionId)
            // Portable imports use UPDATE OR ABORT for existing rows, even on identical backups.
            db.openHelper.writableDatabase.execSQL("UPDATE OR ABORT local_sessions SET name='Imported title' WHERE id='s'")
            db.openHelper.writableDatabase.execSQL("UPDATE OR ABORT local_sessions SET name='Imported again' WHERE id='s'")
            assertEquals("s",db.companionMemoryDao().owner("c")?.sessionId)
            db.sessionDao().upsert(s.copy(id="unrelated",characterId="new",companionMode=false,longConversationEnabled=false))
        } finally { db.close() }
    }

    @Test fun targetAndReferenceAreDisjointAndOnlyTargetReceiptsCommit() = runBlocking<Unit> {
        val db=db(); try {
            val s=setup(db); val rows=backlog(db); val store=CompanionMemoryStore(db)
            val targetIds=mutableSetOf<String>(); val referenceIds=mutableSetOf<String>()
            store.process(s,{prompt,material ->
                assertTrue(prompt.contains("绝不能提取 reference"))
                val m=JsonParser.parseString(material).asJsonObject
                val target=m["target"].asJsonArray.map { it.asJsonObject["id"].asString }.toSet()
                val reference=m["reference"].asJsonArray.map { it.asJsonObject["id"].asString }.toSet()
                assertTrue(target.intersect(reference).isEmpty()); targetIds+=target; referenceIds+=reference
                if("u0" in target) response(rows.first()) else "{\"changes\":[]}"
            },24_000)
            val scope=store.ensureScope(s)
            val pending=db.companionMemoryDao().pending(scope.id,Int.MAX_VALUE).map { it.id }.toSet()
            assertTrue(targetIds.isNotEmpty()); assertTrue(referenceIds.isNotEmpty())
            assertTrue(pending.containsAll(referenceIds)); assertTrue(pending.intersect(targetIds).isEmpty())
            assertEquals("用户怕辣",store.validatedMemories(scope).single { it.current }.content)
            store.process(s,{_,_->error("Do not reprocess committed batches")},24_000)
        } finally { db.close() }
    }

    @Test fun referenceOnlySourceFailsAtomicallyAndRetryDoesNotDuplicate() = runBlocking<Unit> {
        val db=db(); try {
            val s=setup(db); val rows=backlog(db); val store=CompanionMemoryStore(db)
            val scope=store.ensureScope(s); val count=db.companionMemoryDao().pending(scope.id,Int.MAX_VALUE).size
            fails { store.process(s,{_,_->response(rows[rows.size-2])},24_000) }
            assertEquals(count,db.companionMemoryDao().pending(scope.id,Int.MAX_VALUE).size)
            assertTrue(store.validatedMemories(store.ensureScope(s)).isEmpty())
            store.process(s,{_,material -> if(material.contains("\"id\":\"u0\"")) response(rows.first()) else "{\"changes\":[]}"},24_000)
            assertEquals(1,store.validatedMemories(store.ensureScope(s)).count { it.current })
        } finally { db.close() }
    }

    @Test fun manualProtectionIsIndependentFromPinnedAndNewTopicCanReturnAfterDeletion() = runBlocking<Unit> {
        val db=db(); try {
            val s=setup(db); val store=CompanionMemoryStore(db)
            store.editFused(s,"diet","用户怕辣","user_profile",emptyList(),false,true)
            val row=say(db,"current","现在我喜欢辣")
            fails { store.fusedTool(s,"agent_memory_update",mapOf("key" to "diet","operation" to "update",
                "category" to "user_profile","content" to "用户喜欢辣","quote" to row.content),row.id) }
            assertFalse(store.validatedMemories(store.ensureScope(s)).single().alwaysProvide)
            store.editFused(s,"diet","用户怕辣","user_profile",emptyList(),false,false)
            store.fusedTool(s,"agent_memory_update",mapOf("key" to "diet","operation" to "update",
                "category" to "user_profile","content" to "用户喜欢辣","quote" to row.content),row.id)
            assertEquals("用户喜欢辣",store.validatedMemories(store.ensureScope(s)).single { it.current }.content)
            store.deleteFused(s,"diet")
            val fresh=say(db,"fresh","现在我喜欢辣")
            store.fusedTool(s,"agent_memory_update",mapOf("key" to "diet","operation" to "add",
                "category" to "user_profile","content" to "用户喜欢辣","quote" to fresh.content),fresh.id)
            assertEquals("用户喜欢辣",store.validatedMemories(store.ensureScope(s)).single { it.current }.content)
        } finally { db.close() }
    }

    @Test fun cachedVectorQueryHasNoDocumentEmbeddingAndTimesOutToKeywords() = runBlocking<Unit> {
        val db=db(); try {
            val s=setup(db); val scope=CompanionMemoryStore(db).ensureScope(s)
            val calls=mutableListOf<List<String>>()
            var slow=false
            val search=CompanionHybridSearch(db) { CompanionEmbeddingBackend("4096-model") { inputs ->
                calls+=inputs; if(slow) delay(2500); inputs.map { floatArrayOf(1f,0f) }
            } }
            val items=listOf(CompanionSearchItem("fact","f","怕辣"))
            assertTrue(search.cachedScores(scope,"提问",items).isEmpty()); assertTrue(calls.isEmpty())
            search.index(scope,items); calls.clear()
            assertEquals(1,search.cachedScores(scope,"提问",items).size)
            assertEquals(listOf(listOf("提问")),calls)
            slow=true; val started=System.currentTimeMillis()
            assertTrue(search.cachedScores(scope,"超时",items).isEmpty())
            assertTrue(System.currentTimeMillis()-started < 2100)
            val other=CompanionHybridSearch(db) { CompanionEmbeddingBackend("new-dimension") { error("No cached docs in this space") } }
            assertTrue(other.cachedScores(scope,"问题",items).isEmpty())
        } finally { db.close() }
    }

    @Test fun consecutiveRequestsKeepPrefixAndManualEditInvalidatesAuxiliaryCache() = runBlocking<Unit> {
        val db=db(); try {
            val s=setup(db); val now=ZonedDateTime.parse("2026-10-08T20:00:00+08:00[Asia/Shanghai]")
            val first=say(db,"u1","你好")
            val a=FusedCompanionContext(db).build(s,first.id,emptyList(),"STABLE ROLE",emptyList(),
                mapOf("role" to "user","content" to first.content),"",32000,4000,now)
            say(db,"a1","晚上好","assistant"); val next=say(db,"u2","再聊一会")
            val history=db.messageDao().listBySession("s").filter { it.id!=next.id }
            val b=FusedCompanionContext(db).build(s,next.id,history,"STABLE ROLE",emptyList(),
                mapOf("role" to "user","content" to next.content),"",32000,4000,now.plusMinutes(2))
            assertEquals(a,b.take(a.size))
            assertFalse(b[b.size-2]["content"].toString().contains("当前日期及时段"))
            val store=CompanionMemoryStore(db)
            store.editFused(s,"diet","用户怕辣","user_profile",emptyList(),true,false)
            val c=FusedCompanionContext(db).build(s,next.id,history,"STABLE ROLE",emptyList(),
                mapOf("role" to "user","content" to next.content),"",32000,4000,now.plusMinutes(3))
            assertTrue(c[c.size-2]["content"].toString().contains("用户怕辣"))
            assertEquals(3,db.messageDao().listBySession("s").size) // Auxiliary text never becomes chat evidence.
            assertEquals(1,db.companionMemoryDao().contextMaterials(store.ensureScope(s).id).size)
        } finally { db.close() }
    }

    @Test fun tinyContextFailsWithoutDroppingUnprocessedRawAndPinnedOverflowIsExplicit() = runBlocking<Unit> {
        val db=db(); try {
            val s=setup(db); say(db,"old","原话".repeat(5000)); say(db,"a","回复","assistant")
            val current=say(db,"now","接着说")
            fails { FusedCompanionContext(db).build(s,current.id,db.messageDao().listBySession("s").dropLast(1),
                "ROLE",emptyList(),mapOf("role" to "user","content" to "接着说"),"",2000,800) }
            assertEquals(3,db.messageDao().listBySession("s").size)
            val store=CompanionMemoryStore(db)
            repeat(4) { store.editFused(s,"p$it","常驻内容".repeat(300),"user_profile",emptyList(),true,false) }
            fails { store.fusedRecall(s,"问题",FusedCompanionPolicy.budgets(14000)) }
        } finally { db.close() }
    }

    @Test fun eventUpdatesRetractionAndClarificationReuseOneCanonicalRecord() = runBlocking<Unit> {
        val db=db(); try {
            val s=setup(db); val store=CompanionMemoryStore(db)
            suspend fun tool(id: String, text: String, operation: String, content: String, key: String="walk", extra: Map<String,Any> = emptyMap()) {
                val row=say(db,id,text)
                store.fusedTool(s,"agent_memory_update", mapOf("key" to key,"target_key" to key,
                    "operation" to operation,"category" to "episode","content" to content,"quote" to text)+extra,row.id)
            }
            tool("start","今天我们出发散步了","add","散步进行中")
            tool("end","散步回来后一起吃了晚饭","update","散步后一起吃了晚饭")
            var scope=store.ensureScope(s)
            assertEquals(1,store.validatedMemories(scope).count { it.current })
            assertEquals(2,db.companionMemoryDao().versions(scope.id,"walk").size)
            tool("denial","你记错了，我们并没有一起吃晚饭","retract","撤销一起吃晚饭的错误记录")
            scope=store.ensureScope(s)
            assertFalse(store.validatedMemories(scope).any { it.current })
            assertEquals("retracted",db.companionMemoryDao().versions(scope.id,"walk").first().status)
            tool("tea","我喝茶不加糖","add","喝茶不加糖","tea")
            tool("different","今天茶里加了糖","clarify","茶里加糖","tea",mapOf("question" to "现在喝茶要加糖了吗？"))
            val question=store.questionForReply(s)!!
            say(db,"question",question,"assistant"); store.recordClarificationQuestion(s,"question")
            tool("repeat","今天的茶加了糖","clarify","今天加糖","tea",mapOf("question" to "改成加糖了吗？"))
            assertNull(store.questionForReply(s))
            val pending=db.companionMemoryDao().clarifications(scope.id).single()
            tool("confirm","是的","resolve","今天加糖","tea",mapOf("conflict_id" to pending.memoryId))
            assertTrue(db.companionMemoryDao().clarifications(scope.id).isEmpty())
            assertEquals("今天加糖",store.validatedMemories(store.ensureScope(s)).single { it.current }.content)
        } finally { db.close() }
    }

    @Test fun retryAfterDatabaseReopenAndEditedSourceCannotCommitStaleExtraction() = runBlocking<Unit> {
        val name="fused-restart-${java.util.UUID.randomUUID()}.db"
        var db=Room.databaseBuilder(context,NekobotDatabase::class.java,name).build()
        try {
            val s=setup(db); val rows=backlog(db)
            fails { CompanionMemoryStore(db).process(s,{_,_->error("模型失败")},24000) }
            db.close(); db=Room.databaseBuilder(context,NekobotDatabase::class.java,name).build()
            fails { CompanionMemoryStore(db).process(s,{_,_->
                db.messageDao().upsert(rows.first().copy(content="我已经修改了原话"))
                response(rows.first())
            },24000) }
            assertTrue(CompanionMemoryStore(db).validatedMemories(CompanionMemoryStore(db).ensureScope(s)).isEmpty())
            CompanionMemoryStore(db).process(s,{_,_->"{\"changes\":[]}"},24000)
            CompanionMemoryStore(db).process(s,{_,_->error("已提交的旧段不能重复整理")},24000)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
