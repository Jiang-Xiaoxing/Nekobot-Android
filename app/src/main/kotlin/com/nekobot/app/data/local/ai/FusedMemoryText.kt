package com.nekobot.app.data.local.ai

import java.util.Locale

/** 非界面流程错误；弹窗与等待态资源在预览获批后接入。 */
internal enum class FusedMemoryText(private val zh: String, private val tw: String, private val en: String,
    private val ja: String, private val ko: String) {
    INCOMPLETE("总结记忆输出未完整结束，下次发消息会再试", "總結記憶輸出未完整結束，下次傳送訊息會再試",
        "Memory summarization did not finish. It will retry when you send the next message.",
        "記憶の要約が完了しませんでした。次のメッセージ送信時に再試行します。", "기억 요약이 완료되지 않았습니다. 다음 메시지를 보낼 때 다시 시도합니다."),
    DECISION("请先确认相关记忆的处理方式", "請先確認相關記憶的處理方式", "Choose what to do with the linked memories first.",
        "関連する記憶の扱いを先に選んでください。", "연결된 기억의 처리 방법을 먼저 선택해 주세요."),
    SOURCE_CHANGED("原消息已变化，请重新确认", "原訊息已變更，請重新確認", "The original message changed. Please review it again.",
        "元のメッセージが変更されました。もう一度確認してください。", "원본 메시지가 변경되었습니다. 다시 확인해 주세요."),
    RETRY_CHANGED("总结期间原话发生变化，下次发消息重新检查", "總結期間原話發生變更，下次傳送訊息時重新檢查",
        "Messages changed during summarization. They will be checked when you send the next message.",
        "要約中に元のメッセージが変更されました。次の送信時に再確認します。", "요약 중 원본 메시지가 변경되었습니다. 다음 메시지를 보낼 때 다시 확인합니다."),
    TURN_TOO_LARGE("完整一轮原话超过总结记忆批次容量，本轮保留原话，下次发消息再试", "完整一輪原話超過總結記憶批次容量，本輪保留原話，下次傳送訊息再試",
        "A full turn exceeds the memory batch limit. Its messages are kept for a later retry.",
        "1ターン分が記憶要約の上限を超えています。元のメッセージを保持して次回再試行します。", "대화 한 턴이 기억 요약 한도를 초과했습니다. 원본을 보존하고 다음에 다시 시도합니다."),
    MODEL_TOO_SMALL("完整一轮原话超过总结记忆模型容量，原话保留待重试", "完整一輪原話超過總結記憶模型容量，原話保留待重試",
        "A full turn exceeds the summarization model capacity. Its messages are kept for retry.",
        "1ターン分が要約モデルの容量を超えています。元のメッセージを保持して再試行を待ちます。", "대화 한 턴이 요약 모델 용량을 초과했습니다. 원본을 보존하여 재시도를 기다립니다."),
    CONTEXT_TOO_LARGE("本轮消息与核心记忆合计超过模型容量，请精简内容或选择更大上下文模型；原资料保留。",
        "本輪訊息與核心記憶合計超過模型容量，請精簡內容或選擇更大上下文模型；原資料保留。",
        "This message and core memories exceed the model capacity. Shorten the message or choose a larger context model. Your data is kept.",
        "今回のメッセージとコア記憶がモデルの容量を超えています。内容を短くするか、より大きいコンテキストのモデルを選んでください。元の資料は保持されます。",
        "현재 메시지와 핵심 기억이 모델 용량을 초과했습니다. 내용을 줄이거나 더 큰 문맥의 모델을 선택해 주세요. 원본 자료는 보존됩니다.");

    fun value(language: String = Locale.getDefault().toLanguageTag()): String = when {
        language.startsWith("zh-TW", true) || language.startsWith("zh-Hant", true) -> tw
        language.startsWith("en", true) -> en
        language.startsWith("ja", true) -> ja
        language.startsWith("ko", true) -> ko
        else -> zh
    }
}
