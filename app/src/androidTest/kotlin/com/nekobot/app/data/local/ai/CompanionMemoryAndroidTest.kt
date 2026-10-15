package com.nekobot.app.data.local.ai

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.LocalAgentRecallReader
import com.nekobot.app.data.local.AGENT_CONTEXT_SUMMARY_PREFIX
import com.nekobot.app.data.local.AGENT_CONTEXT_SUMMARY_SOURCE
import com.nekobot.app.data.local.db.*
import java.net.ServerSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.PortableDataArchiveManager
import com.nekobot.app.data.local.PortableDataCategory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.net.ssl.SSLContext
import java.security.KeyStore
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic fixtures only. The only HTTP endpoint is a loopback server created by a test. */
@RunWith(AndroidJUnit4::class)
class CompanionMemoryAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val gson = Gson()
    private fun database() = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
    private suspend fun session(db: NekobotDatabase, id: String = "same-session", character: String = "same-character"): LocalSessionEntity {
        db.characterDao().upsert(LocalCharacterEntity(character,"Fixture companion",systemPrompt="ROLE_IDENTITY",createdAt="now",updatedAt="now"))
        val s = LocalSessionEntity(id,"Fixture",characterId=character,sessionMode="agent",inheritCharacter=true,
            longConversationEnabled=true,createdAt="now",updatedAt="now")
        db.sessionDao().upsert(s)
        CompanionMemoryStore(db).ensureScope(s)
        return s
    }
    private suspend fun say(db: NekobotDatabase, s: LocalSessionEntity, id: String, text: String,
        role: String = "user", day: String = "2026-10-05") = LocalMessageEntity(
        id, s.id, role, text, timestamp=day,createdAt=day).also { db.messageDao().upsert(it) }
    private fun change(row: LocalMessageEntity, key: String, content: String = row.content,
        operation: String = "add", kind: String = "fact", evidence: String = "") = mapOf(
        "key" to key,"operation" to operation,"kind" to kind,"subject" to "用户","content" to content,
        "source_id" to row.id,"quote" to row.content,"event_evidence" to evidence,"tags" to listOf(key))
    private fun response(vararg changes: Map<String, Any>) = gson.toJson(mapOf("changes" to changes.toList()))
    private suspend fun facts(db: NekobotDatabase, s: LocalSessionEntity): List<CompanionMemoryEntity> {
        val store = CompanionMemoryStore(db); return store.validatedMemories(store.ensureScope(s))
    }

    @Test fun shortMessagesAndMultipleBatchesAreFullyProcessedAndIdempotent() = runBlocking {
        val db = database()
        try {
            val s=session(db); val rows=mutableListOf<LocalMessageEntity>()
            say(db,s,"command","/help")
            for (i in 0..15) {
                rows += say(db,s,"u$i", if(i==7) "我怕辣" else "事实$i" + "很长".repeat(30))
                say(db,s,"a$i","知道了","assistant")
            }
            val seen=mutableListOf<String>()
            val store=CompanionMemoryStore(db)
            val changed=store.process(s, { _, material ->
                val batch=JsonParser.parseString(material).asJsonObject["messages"].asJsonArray
                seen += batch.map { it.asJsonObject["id"].asString }
                response(*batch.filter { it.asJsonObject["role"].asString=="user" }.map {
                    val r=rows.single { row -> row.id==it.asJsonObject["id"].asString }
                    change(r,"fact-${r.id}")
                }.toTypedArray())
            }, inputBudgetChars=1200)
            assertEquals(16,changed); assertEquals(32,seen.size); assertEquals(32,seen.distinct().size)
            assertEquals(0,store.process(s,{_,_->error("Already processed messages must not call a model")}))
            assertTrue(facts(db,s).any { it.content=="我怕辣" })
            assertTrue(db.companionMemoryDao().pending(store.ensureScope(s).id).isEmpty())
        } finally { db.close() }
    }

    @Test fun turnLargerThanNormalBatchUsesAvailableWindowAndRetainsItsLastSentence() = runBlocking {
        val db=database()
        try {
            val s=session(db); val store=CompanionMemoryStore(db)
            val row=say(db,s,"long","前面的聊天".repeat(2500)+"最后要记住我怕辣")
            val reply=say(db,s,"reply","收到，尾部也保留。","assistant")
            val next=say(db,s,"next","我还喜欢茶")
            var calls=0
            store.process(s,{_,material->
                calls++
                val messages=JsonParser.parseString(material).asJsonObject["messages"].asJsonArray
                if(calls==1) {
                    assertEquals(listOf(row.id,reply.id),messages.map { it.asJsonObject["id"].asString })
                    assertEquals(row.content,messages.first().asJsonObject["text"].asString)
                    assertTrue(material.contains("最后要记住我怕辣"))
                    response(change(row,"diet",content="我怕辣"))
                } else response(change(next,"tea"))
            },inputBudgetChars=30000)
            assertEquals(2,calls); assertEquals(2,facts(db,s).size)
        } finally { db.close() }
    }

    @Test fun broadDietQuestionRecallsEarlySourcedPreferenceAfterUnrelatedLongHistory() = runBlocking {
        val db=database()
        try {
            val s=session(db); val store=CompanionMemoryStore(db)
            val diet=say(db,s,"diet","我怕辣。")
            store.process(s,{_,_->response(change(diet,"diet",content="用户怕辣"))})
            repeat(19) { i ->
                say(db,s,"noise-u-$i","无关材料"+"石头".repeat(250),day="2026-10-06")
                say(db,s,"noise-a-$i","普通回应"+"清风".repeat(250),"assistant",day="2026-10-06")
            }
            val rows=db.messageDao().listBySession(s.id)
            val recalled=store.recall(s,"我吃东西有什么特别偏好？",rows.takeLast(6),6000)
            assertTrue(recalled.contains("用户怕辣")); assertTrue(recalled.contains("\"message_id\":\"diet\""))
            assertFalse(recalled.contains("石头石头")); assertTrue(recalled.length<=6000)
            val exact=store.recall(s,"我吃东西有什么偏好，那条原话是什么？",rows.takeLast(6),6000)
            assertTrue(exact.contains("\"verbatim\":\"我怕辣。\"")); assertTrue(exact.length<=6000)
            store.lock(s,"diet",true)
            val locked=store.recall(s,"我吃东西有什么特别偏好？",rows.takeLast(6),6000)
            assertTrue(locked.contains("\"locked\":true")); assertTrue(locked.contains("不能宣称已覆盖"))
            assertTrue(locked.length<=6000)
        } finally { db.close() }
    }

    @Test fun failureAndDatabaseReopenResumeWithoutSkippingOrDuplicating() = runBlocking {
        val name="companion-resume-${UUID.randomUUID()}.db"
        var db=Room.databaseBuilder(context,NekobotDatabase::class.java,name).build()
        try {
            val s=session(db); val source=say(db,s,"short","记住我不吃辣")
            try { CompanionMemoryStore(db).process(s,{_,_->error("fixture failure")}); fail() } catch (_: IllegalStateException) {}
            val scope=CompanionMemoryStore(db).ensureScope(s)
            assertEquals(1,db.companionMemoryDao().pending(scope.id).size)
            db.close(); db=Room.databaseBuilder(context,NekobotDatabase::class.java,name).build()
            assertEquals(1,CompanionMemoryStore(db).process(s,{_,_->response(change(source,"diet"))}))
            assertEquals(1,facts(db,s).size)
            assertEquals(0,CompanionMemoryStore(db).process(s,{_,_->error("must not retry committed work")}))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun explicitCorrectionCancellationCompletionAndManualLockPreserveHistory() = runBlocking {
        val db=database()
        try {
            val s=session(db); val store=CompanionMemoryStore(db)
            val first=say(db,s,"first","明天复诊")
            store.process(s,{_,_->response(change(first,"visit",kind="task",evidence="明天"))})
            val corrected=say(db,s,"correct","改成2026年10月8日复诊")
            store.process(s,{_,_->response(change(corrected,"visit",operation="update",kind="task",evidence="2026年10月8日"))})
            val cancelled=say(db,s,"cancel","取消复诊")
            store.process(s,{_,_->response(change(cancelled,"visit",operation="cancel",kind="task"))})
            assertEquals(3,facts(db,s).size); assertEquals("cancelled",facts(db,s).single { it.current }.status)
            assertEquals("2026-10-06",facts(db,s).single { it.version==1 }.eventDate)
            store.lock(s,"visit",true)
            val complete=say(db,s,"complete","复诊完成了")
            store.process(s,{_,_->response(change(complete,"visit",operation="complete",kind="task"))})
            assertEquals("cancelled",facts(db,s).single { it.current }.status)
            store.lock(s,"visit",false)
            val done=say(db,s,"done","复诊已经完成")
            store.process(s,{_,_->response(change(done,"visit",operation="complete",kind="task"))})
            assertEquals("completed",facts(db,s).single { it.current }.status)
            assertEquals(4,facts(db,s).size)
        } finally { db.close() }
    }

    @Test fun ambiguityRoleplayAndAssistantGuessesDoNotBecomeUserFacts() = runBlocking {
        val db=database()
        try {
            val s=session(db); val store=CompanionMemoryStore(db)
            val hypothesis=say(db,s,"fiction","假设我明天生日")
            assertEquals(0,store.process(s,{_,_->response(change(hypothesis,"birthday",kind="birthday",evidence="明天"))}))
            val assistant=say(db,s,"guess","我猜你的生日是10月6日","assistant")
            try { store.process(s,{_,_->response(change(assistant,"birthday",kind="birthday"))}); fail() } catch (_: IllegalArgumentException) {}
            assertTrue(facts(db,s).isEmpty())
            val scope=store.ensureScope(s); assertEquals(1,db.companionMemoryDao().pending(scope.id).size)
        } finally { db.close() }
    }

    @Test fun modelGeneratedChildKeyCannotBypassLockedFactByBecomingAnotherActivePreference() = runBlocking {
        val db=database()
        try {
            val s=session(db); val store=CompanionMemoryStore(db)
            val first=say(db,s,"first","我怕辣。")
            store.process(s,{_,_->response(change(first,"user_dietary_preference_spicy"))})
            store.lock(s,"user_dietary_preference_spicy",true)
            val corrected=say(db,s,"corrected","改成我很喜欢吃辣。")
            store.process(s,{_,_->response(change(corrected,"user_dietary_preference_spicy_conflict"))})
            val saved=facts(db,s)
            assertEquals(1,saved.count { it.current }); assertTrue(saved.single { it.current }.locked)
            assertTrue(saved.any { it.status=="conflict" && !it.current && it.content.contains("喜欢吃辣") })
        } finally { db.close() }
    }

    @Test fun uncertainContradictionDoesNotSilentlyOverwriteCurrentFact() = runBlocking {
        val db=database()
        try {
            val s=session(db); val store=CompanionMemoryStore(db)
            val first=say(db,s,"first","我喜欢红色"); store.process(s,{_,_->response(change(first,"color"))})
            val other=say(db,s,"other","我喜欢蓝色"); store.process(s,{_,_->response(change(other,"color"))})
            assertEquals("我喜欢红色",facts(db,s).single { it.current }.content)
            assertTrue(facts(db,s).any { it.status=="conflict" && !it.current })
        } finally { db.close() }
    }

    @Test fun sourceEditSoftDeleteHardDeleteAndForgetInvalidateEveryReadSurface() = runBlocking {
        val db=database()
        try {
            val s=session(db); val store=CompanionMemoryStore(db)
            val source=say(db,s,"diet","记住我不吃辣"); store.process(s,{_,_->response(change(source,"忌辣"))})
            db.messageDao().updateContent(source.id,"我喜欢甜食")
            assertTrue(facts(db,s).isEmpty())
            val edited=db.messageDao().getById(source.id)!!
            store.process(s,{_,_->response(change(edited,"甜食"))})
            assertEquals(1,facts(db,s).size)
            assertTrue(store.forgetRequested(s,"忘记甜食"))
            assertTrue(facts(db,s).isEmpty())
            assertFalse(store.recall(s,"甜食原话是什么",emptyList(),6000).contains("我喜欢甜食"))
            val reader=LocalAgentRecallReader(db)
            assertTrue(reader.searchHistory(s.id,listOf("甜食"),48).isEmpty())
            assertTrue(reader.readHistory(s.id,source.id,2,2).isEmpty())
            val second=say(db,s,"second","我爱茶"); store.process(s,{_,_->response(change(second,"茶"))})
            db.messageDao().updateDeleted(second.id,true); assertTrue(facts(db,s).isEmpty())
            val third=say(db,s,"third","我爱音乐"); store.process(s,{_,_->response(change(third,"音乐"))})
            db.messageDao().deleteById(third.id); assertTrue(facts(db,s).isEmpty())
        } finally { db.close() }
    }

    @Test fun sameIdsAcrossDatabasesAndDifferentCharactersCannotLeakOrAcceptLateResults() = runBlocking {
        val db=database(); val other=database()
        try {
            val s=session(db); val b=session(other)
            val old=say(db,s,"same-message","我不吃辣")
            CompanionMemoryStore(db).process(s,{_,_->response(change(old,"diet"))})
            assertTrue(facts(other,b).isEmpty())
            val isolated=session(db,id="another-session")
            assertTrue(facts(db,isolated).isEmpty())
            assertFalse(CompanionMemoryStore(db).recall(isolated,"我不吃辣",emptyList(),6000).contains("我不吃辣"))
            val pending=say(db,s,"pending","记住我的生日")
            try {
                CompanionMemoryStore(db).process(s,{_,_->
                    db.sessionDao().update(s.copy(characterId="new-character"))
                    response(change(pending,"birthday"))
                }); fail()
            } catch (_: IllegalArgumentException) {}
            val changed=db.sessionDao().getById(s.id)!!
            val store=CompanionMemoryStore(db); store.ensureScope(changed)
            assertTrue(facts(db,changed).isEmpty())
            assertFalse(store.recall(changed,"忌辣和生日",emptyList(),6000).contains("我不吃辣"))
            assertFalse(store.permittedHistory(changed,listOf(old)).contains(old))
        } finally { db.close(); other.close() }
    }

    @Test fun switchingOffPausesWritesAndDoesNotQueueOrdinaryModeMessagesForMigration() = runBlocking {
        val db=database()
        try {
            val s=session(db); val store=CompanionMemoryStore(db)
            db.sessionDao().update(s.copy(longConversationEnabled=false))
            say(db,s,"ordinary","普通模式的新事实")
            db.sessionDao().update(s)
            assertEquals(0,store.process(s,{_,_->error("ordinary messages must not be migrated")}))
            assertTrue(facts(db,s).isEmpty())
        } finally { db.close() }
    }

    @Test fun manyStoredFactsKeepEarlyChineseParaphraseRecallWithinInjectionBudget() = runBlocking {
        val db=database()
        try {
            val s=session(db); val store=CompanionMemoryStore(db)
            val early=say(db,s,"early","我不吃辣",day="2026-01-01")
            store.process(s,{_,_->response(change(early,"饮食偏好"))})
            for(i in 0..150) {
                val row=say(db,s,"later-$i","项目${i}的名称是小山$i")
                store.process(s,{_,_->response(change(row,"project-$i"))})
            }
            assertEquals(152,facts(db,s).size)
            val text=store.recall(s,"还记得我的忌辣吗",emptyList(),6000)
            assertTrue(text.contains("我不吃辣")); assertTrue(text.length <= 6000)
            assertFalse(text.contains("项目150"))
        } finally { db.close() }
    }

    @Test fun actualPipelineAndLoopbackHttpSendFourBlocksCurrentDayAndLatestQuestionOnce() = runBlocking {
        val db=database()
        val executor=Executors.newSingleThreadExecutor()
        val keys=KeyStore.getInstance("PKCS12").apply {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
                .open("companion-loopback.p12").use { load(it,"fixturepass".toCharArray()) }
        }
        val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys,"fixturepass".toCharArray()) }
        val tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val tls=SSLContext.getInstance("TLS").apply { init(km.keyManagers,tm.trustManagers,null) }
        val client=LocalAiClient(OkHttpClient.Builder().sslSocketFactory(tls.socketFactory,tm.trustManagers.single() as X509TrustManager).build())
        val server=tls.serverSocketFactory.createServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        try {
            val s=session(db); val store=CompanionMemoryStore(db)
            val fact=say(db,s,"birthday","明天是我的生日")
            store.process(s,{_,_->response(change(fact,"生日",kind="birthday",evidence="明天"))})
            val answer=say(db,s,"answer","我会陪你过生日，晚安。","assistant")
            db.messageDao().upsert(LocalMessageEntity("old-summary",s.id,"system","$AGENT_CONTEXT_SUMMARY_PREFIX\nFORBIDDEN_LIFETIME_SUMMARY",
                timestamp="2026-10-06",createdAt="2026-10-06",source="$AGENT_CONTEXT_SUMMARY_SOURCE:${answer.id}"))
            db.experienceArchiveDao().saveGenerated(LocalExperienceArchiveEntity(
                "archive",s.id,characterId=s.characterId,startMessageId=fact.id,endMessageId=answer.id,
                sourceStartedAt=fact.createdAt,sourceEndedAt=answer.createdAt,summary="生日约定",tagsJson="[\"生日\"]",
                sourceFingerprint="fixture",createdAt="now",updatedAt="now"),listOf(fact.id,answer.id))
            // Force bounded selection/cropping to remove unrelated dialogue while relevant
            // early evidence remains available through automatic recall.
            for(i in 0..18) {
                say(db,s,"noise-user-$i","无关项目$i "+"石头".repeat(250),day="2026-10-06")
                say(db,s,"noise-role-$i","普通回应$i "+"清风".repeat(250),"assistant",day="2026-10-06")
            }
            val latest=say(db,s,"latest","关于我的生日，当时你是怎么说的原话？",day="2026-10-07")
            val model=LocalAiModelEntity("offline","Loopback",protocol="openai_chat",apiKey="",baseUrl="https://127.0.0.1:${server.localPort}/v1",model="fixture",createdAt="now")
            var sharedReads=0
            val runtime=CharacterRuntime(profileRepo=object: ProfileRepository {
                override suspend fun getById(id:String)=CharacterProfile(id=id,name="Fixture",systemPrompt="ROLE_IDENTITY")
            }, memoryService=object: MemoryService {
                override suspend fun search(identity:CharacterIdentity,content:String,limit:Int):List<CharacterMemory> { sharedReads++; return emptyList() }
                override suspend fun extractIfNeeded(chatRequest:ChatRequest,response:ChatResponse,turnContext:CharacterTurnContext) { error("shared writes forbidden") }
            })
            val cb=LocalPipelineCallbacks(db,LocalAiClient(),model,s,db.characterDao().getById(s.characterId!!),
                characterRuntime=runtime,characterIdentity=CharacterIdentity(s.characterId!!,"local-user",s.id),parentMessageId=latest.id)
            val ctx=PipelineContext(ChatRequest.forLocal(s.id,latest.content,metadata=mapOf("session_mode" to "agent","inherit_character" to true)))
            ctx.metadata.putAll(ctx.chatRequest.metadata)
            AIPipeline().prepareContext(ctx,cb,maxContextChars=8000)
            assertTrue(ctx.messages.size < db.messageDao().countVisibleFinalBySession(s.id))
            val captured=executor.submit<String> {
                server.accept().use { socket ->
                    socket.soTimeout=10_000
                    val input=socket.getInputStream(); val header=StringBuilder()
                    while(!header.endsWith("\r\n\r\n")) { val b=input.read(); if(b<0) error("unexpected EOF"); header.append(b.toChar()) }
                    val length=Regex("Content-Length: (\\d+)",RegexOption.IGNORE_CASE).find(header)!!.groupValues[1].toInt()
                    val body=ByteArray(length); var read=0
                    while(read<length) { val n=input.read(body,read,length-read); if(n<0) error("unexpected EOF"); read+=n }
                    val reply="""{"choices":[{"message":{"role":"assistant","content":"fixture response"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""".toByteArray()
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getOutputStream().write(reply); socket.getOutputStream().flush()
                    String(body,Charsets.UTF_8)
                }
            }
            val result=client.chatOnce(model,ctx.messages)
            assertEquals("fixture response",result.content)
            val payload=JsonParser.parseString(captured.get(10,TimeUnit.SECONDS)).asJsonObject
            java.io.File(context.getExternalFilesDir(null),"companion-outbound.json").writeText(payload.toString())
            val text=payload["messages"].asJsonArray.joinToString("\n") { it.asJsonObject["content"].asString }
            assertTrue(text.contains("ROLE_IDENTITY")); assertTrue(text.contains("独立长期记忆"))
            assertTrue(text.contains("生日约定")); assertTrue(text.contains("我会陪你过生日"))
            assertTrue(text.contains("2026-10-06")); assertTrue(text.contains(java.time.LocalDate.now().toString()))
            assertEquals(1,Regex(Regex.escape(latest.content)).findAll(text).count())
            assertFalse(text.contains("FORBIDDEN_LIFETIME_SUMMARY")); assertEquals(0,sharedReads)
            assertFalse((cb.executeTool("agent_memory_update",emptyMap(),emptyMap())["success"] as Boolean))
            // Ordinary mode still invokes the original memory service.
            runtime.beforeTurn(ctx.chatRequest,CharacterIdentity(s.characterId!!,"local-user",s.id))
            assertEquals(1,sharedReads)
        } finally { server.close(); executor.shutdownNow(); db.close() }
    }

    @Test fun cancelledAndEditedInFlightExtractionKeepQueueUntilSuccessfulRetry() = runBlocking {
        val db=database()
        try {
            val s=session(db); val store=CompanionMemoryStore(db)
            val row=say(db,s,"pending","我喜欢茶")
            try { store.process(s,{_,_->throw kotlinx.coroutines.CancellationException("fixture stop")}); fail() }
            catch (_: kotlinx.coroutines.CancellationException) {}
            val scope=store.ensureScope(s)
            assertEquals(1,db.companionMemoryDao().pending(scope.id).size)
            try { store.process(s,{_,_->
                db.messageDao().updateContent(row.id,"我喜欢咖啡")
                response(change(row,"drink"))
            }); fail() } catch (_: IllegalArgumentException) {}
            assertTrue(facts(db,s).isEmpty())
            val edited=db.messageDao().getById(row.id)!!
            assertEquals(1,store.process(s,{_,_->response(change(edited,"drink"))}))
            assertEquals("我喜欢咖啡",facts(db,s).single().content)
        } finally { db.close() }
    }

    @Test fun forgettingRawOnlyEvidenceInvalidatesArchiveAndKeepsUnrelatedInformation() = runBlocking {
        val db=database()
        try {
            val s=session(db); val store=CompanionMemoryStore(db)
            val secret=say(db,s,"private","我的门牌是八十八号")
            val answer=say(db,s,"private-answer","记住门牌了","assistant")
            val other=say(db,s,"other","我喜欢茶")
            db.experienceArchiveDao().saveGenerated(LocalExperienceArchiveEntity("private-archive",s.id,characterId=s.characterId,
                startMessageId=secret.id,endMessageId=answer.id,sourceStartedAt=secret.createdAt,sourceEndedAt=answer.createdAt,
                summary="门牌八十八号",tagsJson="[\"门牌\"]",sourceFingerprint="fixture",createdAt="now",updatedAt="now"),listOf(secret.id,answer.id))
            assertTrue(store.forgetRequested(s,"忘记门牌"))
            assertFalse(store.recall(s,"门牌原话",emptyList(),6000).contains("八十八号"))
            assertTrue(LocalAgentRecallReader(db).searchHistory(s.id,listOf("门牌"),48).isEmpty())
            assertEquals("stale",db.experienceArchiveDao().getById("private-archive")!!.status)
            store.process(s,{_,material->
                assertFalse(material.contains("八十八号"))
                response(change(other,"drink"))
            })
            assertEquals("我喜欢茶",facts(db,s).single().content)
            assertNotNull(db.messageDao().getById(secret.id)) // scoped exclusion preserves the original chat
        } finally { db.close() }
    }

    @Test fun dateLookupAndRoleStyleSamplesKeepSourceDayAndSpeakerIdentity() = runBlocking {
        val db=database()
        try {
            val s=session(db); val store=CompanionMemoryStore(db)
            say(db,s,"old-user","清风与你同在","user","2026-01-01")
            say(db,s,"old-role","晚安，我会守着这盏灯。","assistant","2026-01-01")
            say(db,s,"guess","我猜晚安时你想搬家","assistant","2026-01-02")
            val date=store.recall(s,"2026年1月1日你说过什么原话",emptyList(),6000)
            assertTrue(date.contains("清风与你同在")); assertTrue(date.contains("2026-01-01"))
            val style=store.recall(s,"用以前晚安的语气陪我",emptyList(),6000)
            assertTrue(style.contains("角色表达样本，不能当用户事实"))
            assertTrue(style.contains("我会守着这盏灯")); assertFalse(style.contains("你想搬家"))
            assertTrue(LocalAgentRecallReader(db).searchHistory(s.id,listOf("2026年1月1日"),48).any { it.id=="old-role" })
        } finally { db.close() }
    }

    @Test fun sourceEditAfterPreparationStopsBothHttpAndToolUseUntilRebuilt() = runBlocking {
        val db=database()
        try {
            val s=session(db); val row=say(db,s,"old","我喜欢茶")
            CompanionMemoryStore(db).process(s,{_,_->response(change(row,"drink"))})
            val model=LocalAiModelEntity("offline","Loopback",protocol="openai_chat",apiKey="",baseUrl="http://127.0.0.1:1/v1",model="fixture",createdAt="now")
            val cb=LocalPipelineCallbacks(db,LocalAiClient(),model,s,db.characterDao().getById(s.characterId!!))
            val ctx=PipelineContext(ChatRequest.forLocal(s.id,"你还记得我的饮食偏好吗"))
            AIPipeline().prepareContext(ctx,cb,maxContextChars=8000)
            db.messageDao().updateContent(row.id,"我喜欢咖啡")
            try { cb.buildModelCall(ctx,emptyList()).invoke(ctx.messages,false); fail("stale request must never reach HTTP") }
            catch (_: IllegalArgumentException) {}
            try { cb.executeTool("agent_history_search",mapOf("terms" to listOf("茶")),emptyMap()); fail() }
            catch (_: IllegalArgumentException) {}
            val rebuilt=PipelineContext(ChatRequest.forLocal(s.id,"你还记得我的饮食偏好吗"))
            val next=LocalPipelineCallbacks(db,LocalAiClient(),model,s,db.characterDao().getById(s.characterId!!))
            AIPipeline().prepareContext(rebuilt,next,maxContextChars=8000)
            assertFalse(rebuilt.messages.toString().contains("我喜欢茶"))
        } finally { db.close() }
    }

    @Test fun sameMessageIdReassignedToAnotherSessionRetiresOldPreparedContext() = runBlocking {
        for (replace in listOf(true,false)) for (rawOnly in listOf(true,false)) {
            val db=database()
            try {
                val s=session(db); val destination=session(db,id="destination")
                if(rawOnly) {
                    db.companionMemoryDao().deleteScopes(s.id)
                    db.sessionDao().update(s.copy(longConversationEnabled=false))
                }
                val source=say(db,s,"same-id","我怕辣")
                if(rawOnly) {
                    db.sessionDao().update(s)
                    val scope=CompanionMemoryStore(db).ensureScope(s)
                    assertTrue(db.companionMemoryDao().ownedMessages(scope.id).isEmpty())
                } else CompanionMemoryStore(db).process(s,{_,_->response(change(source,"diet"))})
                val model=LocalAiModelEntity("offline","Loopback",protocol="openai_chat",apiKey="",baseUrl="http://127.0.0.1:1/v1",model="fixture",createdAt="now")
                val cb=LocalPipelineCallbacks(db,LocalAiClient(),model,s,db.characterDao().getById(s.characterId!!))
                val ctx=PipelineContext(ChatRequest.forLocal(s.id,"我怕辣吗"))
                AIPipeline().prepareContext(ctx,cb,maxContextChars=8000)
                if(replace) db.messageDao().upsert(source.copy(sessionId=destination.id))
                else db.openHelper.writableDatabase.execSQL("UPDATE local_messages SET session_id = ? WHERE id = ?",arrayOf(destination.id,source.id))
                assertTrue(facts(db,s).isEmpty()); assertTrue(facts(db,destination).isEmpty())
                try { cb.buildModelCall(ctx,emptyList()).invoke(ctx.messages,false); fail("old-owner material must not be sent") }
                catch (_:IllegalArgumentException) {}
            } finally { db.close() }
        }
    }

    @Test fun repeatedIndependentArchivesRetainEarlyEpisodesWithoutRollingSummaryInput() = runBlocking {
        val db=database()
        try {
            val s=session(db); val store=CompanionMemoryStore(db)
            for(i in 1..6) {
                say(db,s,"u$i",if(i==1) "那天与小林约好去看海" else "第${i}个月的新话题",day="2026-0$i-01")
                say(db,s,"a$i",if(i==1) "看海时我说，等风来也等你。" else "回应$i","assistant",day="2026-0$i-01")
            }
            val dao=db.companionMemoryDao(); val scope=store.ensureScope(s)
            var calls=0
            val archiver=IncrementalExperienceArchiver(db.experienceArchiveDao(),generate={_,material->
                assertFalse(material.contains("旧终身总摘要")); assertFalse(material.contains("已有滚动摘要"))
                calls++
                gson.toJson(mapOf("summary" to if(material.contains("小林")) "与小林看海的约定" else "普通独立经历$calls","tags" to listOf("经历")))
            },characterId=s.characterId,commitGenerated={candidate,ids,rows->db.withTransaction {
                for(row in rows) require(CompanionMemoryPolicy.fingerprint(db.messageDao().getById(row.id)!!)==CompanionMemoryPolicy.fingerprint(row))
                db.experienceArchiveDao().saveGenerated(candidate,ids)
            }})
            val first=archiver.archiveNewOldMessages(s.id,dao.unarchived(scope.id),"旧终身总摘要",independentSegments=true)
            assertEquals(4,first.generatedCount)
            val second=archiver.archiveNewOldMessages(s.id,dao.unarchived(scope.id),"旧终身总摘要",independentSegments=true)
            assertEquals(2,second.generatedCount); assertEquals(6,calls)
            assertEquals(6,db.experienceArchiveDao().listBySession(s.id,100,0).size)
            val recall=store.recall(s,"与小林约好看海时你说过什么原话",emptyList(),6000)
            assertTrue(recall.contains("与小林看海的约定")); assertTrue(recall.contains("等风来也等你"))
            assertTrue(recall.length<=6000)
        } finally { db.close() }
    }

    @Test fun portableConversationRoundTripKeepsScopedVersionsLocksReceiptsAndForgetting() = runBlocking {
        val original=ServiceContainer.prefs.activeDbName
        val profile="companion-portable-${UUID.randomUUID()}"
        fun ownedDb():NekobotDatabase {
            check(ServiceContainer.prefs.activeDbName==profile)
            return NekobotDatabase.get(context,profile)
        }
        try {
            ServiceContainer.switchLocalDb(profile)
            var db=ownedDb(); val s=session(db); val store=CompanionMemoryStore(db)
            val first=say(db,s,"one","我喜欢茶"); store.process(s,{_,_->response(change(first,"drink"))})
            val correction=say(db,s,"two","改成喜欢咖啡"); store.process(s,{_,_->response(change(correction,"drink",operation="update"))})
            store.lock(s,"drink",true)
            val secret=say(db,s,"private","门牌是八十八号"); store.forgetRequested(s,"忘记门牌")
            val contrary=say(db,s,"contrary","现在我喜欢茶")
            store.process(s,{_,_->response(change(contrary,"drink",operation="update"))})
            val beforeExport=store.ensureScope(s)
            val pending=db.companionMemoryDao().clarifications(beforeExport.id).single()
            db.companionMemoryDao().saveVectors(listOf(CompanionVectorEntity(beforeExport.id,"fact",pending.memoryId,
                "fixture","fingerprint","[1.0]","now")))
            val output=ByteArrayOutputStream(); val manager=PortableDataArchiveManager(context)
            val selected=setOf(PortableDataCategory.CONVERSATIONS,PortableDataCategory.CHARACTERS)
            manager.export(selected,"",output,"companion-fixture")
            db.sessionDao().deleteById(s.id)
            manager.import(ByteArrayInputStream(output.toByteArray()),"",selected)
            db=ownedDb(); val restored=db.sessionDao().getById(s.id)!!
            val recovered=CompanionMemoryStore(db)
            assertEquals(3,facts(db,restored).size); assertTrue(facts(db,restored).filter { it.current }.all { it.locked })
            val scope=recovered.ensureScope(restored)
            assertEquals(db.dbName,scope.profile); assertTrue(db.companionMemoryDao().forgotten(scope.id).contains(secret.id))
            assertEquals(pending.memoryId,db.companionMemoryDao().clarifications(scope.id).single().memoryId)
            assertEquals(1,db.companionMemoryDao().vectors(scope.id,"fixture").size)
            assertEquals(0,recovered.process(restored,{_,_->error("committed and forgotten sources must not call model")}))
            val fresh=say(db,restored,"fresh","我喜欢音乐")
            assertEquals(1,recovered.process(restored,{_,_->response(change(fresh,"music"))}))
            assertFalse(recovered.recall(restored,"门牌",emptyList(),6000).contains("八十八号"))
        } finally {
            ServiceContainer.switchLocalDb(original)
            NekobotDatabase.deleteProfileFile(context,profile)
        }
    }

    @Test fun memoryBranchSuppressesAuthorReadsAndWritesWhileKeepingRoleStateAndWorldBook() = runBlocking {
        var reads=0; val writes=java.util.concurrent.atomic.AtomicInteger()
        val latch=java.util.concurrent.CountDownLatch(1)
        var states=0; var worlds=0
        val runtime=CharacterRuntime(profileRepo=object:ProfileRepository {
            override suspend fun getById(id:String)=CharacterProfile(id=id,name="Fixture",systemPrompt="ROLE_IDENTITY")
        },stateRepo=object:CharacterStateRepository {
            override suspend fun get(characterId:String,scopeId:String):CharacterState?=null
            override suspend fun save(state:CharacterState) { states++ }
        },memoryService=object:MemoryService {
            override suspend fun search(identity:CharacterIdentity,content:String,limit:Int):List<CharacterMemory> { reads++; return emptyList() }
            override suspend fun extractIfNeeded(chatRequest:ChatRequest,response:ChatResponse,turnContext:CharacterTurnContext) {
                writes.incrementAndGet(); latch.countDown()
            }
        },worldBookStore=object:CharacterRuntime.WorldBookStore {
            override suspend fun match(characterId:String,userMessage:String,state:CharacterState?,relationship:RelationshipState?,
                scopeId:String,recentMessages:List<Map<String,String>>):List<CharacterRuntime.WorldBookMatch> {
                worlds++; return listOf(CharacterRuntime.WorldBookMatch("WORLD_BOOK_FACT"))
            }
        })
        val request=ChatRequest.forLocal("s","你好",metadata=mapOf("session_mode" to "role","inherit_character" to true))
        val identity=CharacterIdentity("c","local-user","s")
        val turn=runtime.beforeTurn(request,identity,allowSharedMemory=false)
        assertEquals(0,reads); assertTrue(turn.promptText.contains("ROLE_IDENTITY"))
        assertTrue(turn.promptText.contains("WORLD_BOOK_FACT")); assertEquals(1,worlds)
        runtime.afterTurn(request,"陪着你",turn,allowSharedMemory=false)
        assertEquals(0,writes.get()); assertTrue(states>0)
        val ordinary=runtime.beforeTurn(request,identity)
        assertEquals(1,reads)
        runtime.afterTurn(request,"陪着你",ordinary)
        assertTrue(latch.await(5,TimeUnit.SECONDS)); assertEquals(1,writes.get())
    }

    @Test fun allFiveLocalesResolveNameRiskCostParametersAndUnlockedSwitchText() {
        val locales=listOf("zh-CN" to "长久陪伴","zh-TW" to "長久陪伴","en" to "Lasting companionship",
            "ja" to "ずっと一緒","ko" to "오래 함께하기")
        for ((language,title) in locales) {
            val config=android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            val localized=context.createConfigurationContext(config)
            assertEquals(title,localized.getString(com.nekobot.app.R.string.sessions_detail_long_conversation))
            assertTrue(localized.getString(com.nekobot.app.R.string.sessions_detail_long_conversation_off).contains(title))
            assertTrue(localized.getString(com.nekobot.app.R.string.sessions_long_conversation_mode_risk).length>40)
            val cost=localized.getString(com.nekobot.app.R.string.sessions_long_conversation_cost_notice,"MODEL_FIXTURE","0.01","0.02")
            assertTrue(cost.contains("MODEL_FIXTURE")); assertTrue(cost.contains("0.01")); assertTrue(cost.contains("0.02"))
            assertTrue(localized.getString(com.nekobot.app.R.string.companion_embedding_free_hint).contains("BAAI/bge-m3"))
            assertTrue(localized.getString(com.nekobot.app.R.string.companion_embedding_free_hint).contains("Pro/BAAI/bge-m3"))
            assertFalse(cost.contains("%1\$s")); assertFalse(localized.getString(com.nekobot.app.R.string.sessions_long_conversation_cost_confirm).isBlank())
        }
    }

    @Test fun loadedIndependentMemoryViewRefreshesAfterSourceEditWithoutClearingOriginalChat() = runBlocking {
        val original=ServiceContainer.prefs.activeDbName
        val profile="companion-view-${UUID.randomUUID()}"
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val viewModels=androidx.lifecycle.ViewModelStore()
        try {
            ServiceContainer.switchLocalDb(profile)
            val db=NekobotDatabase.get(context,profile); val s=session(db)
            val row=say(db,s,"source","我喜欢茶")
            CompanionMemoryStore(db).process(s,{_,_->response(change(row,"drink"))})
            db.experienceArchiveDao().saveGenerated(LocalExperienceArchiveEntity("view-archive",s.id,characterId=s.characterId,
                startMessageId=row.id,endMessageId=row.id,sourceStartedAt=row.createdAt,sourceEndedAt=row.createdAt,
                summary="喜欢茶",sourceFingerprint="fixture",createdAt="now",updatedAt="now"),listOf(row.id))
            lateinit var vm:com.nekobot.app.ui.screens.memory.ExperienceArchiveViewModel
            instrumentation.runOnMainSync {
                vm=com.nekobot.app.ui.screens.memory.ExperienceArchiveViewModel()
                viewModels.put("fixture",vm); vm.load(s.id)
            }
            fun await(condition:()->Boolean) { val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
                while(!condition() && System.nanoTime()<deadline) Thread.sleep(20)
                assertTrue(condition())
            }
            await { vm.companionMemories.value.any { it.content=="我喜欢茶" } }
            db.messageDao().updateContent(row.id,"我喜欢咖啡")
            await { vm.companionMemories.value.isEmpty() }
            await { vm.archives.value.singleOrNull()?.status=="stale" }
            assertEquals("我喜欢咖啡",db.messageDao().getById(row.id)!!.content)
        } finally {
            instrumentation.runOnMainSync { viewModels.clear() }
            ServiceContainer.switchLocalDb(original); NekobotDatabase.deleteProfileFile(context,profile)
        }
    }
}
