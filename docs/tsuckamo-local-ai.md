# ローカルLLM・Whisper接続

`1.1.1-tsuckamo.2` で、AIコンシェルジュにローカルLLMとWhisperの組み合わせを追加しました。録画削除機能も引き続き含みます。

## 設定と使い方

設定 → ラボ → 「会話AI・音声認識の設定」を開き、処理先を「ローカルLLM + Whisper」にします。

- 会話AIのURLとモデル名を設定します。OpenAI互換の `POST /v1/chat/completions` を使用します。
- WhisperのURLとモデル名を設定します。`POST /v1/audio/transcriptions` に16kHz・モノラル・PCM16bitのWAV、`language=ja`、`response_format=json`を送ります。
- URLはサーバーのルートまたは `/v1` まで指定できます。リバースプロキシの接頭辞も保持します。
- 認証が必要なサーバーでは、それぞれのトークン欄にBearerトークンを設定します。
- WhisperのURLを空欄にすると、ローカルLLMは文字入力で利用できます。
- Geminiへの切り替えと従来のAPIキー設定も利用できます。

AIパネルのマイクにフォーカスを合わせ、決定キーを長押しして話し、離すと送信します。Whisperの認識結果を画面に表示し、その文字列を会話AIに渡します。端末・リモコンがアプリの音声録音に対応している必要があります。利用できない場合は文字入力を使用してください。

## 動作上の注意

- ローカルモードでは、失敗してもGeminiへ自動フォールバックしません。
- 会話AIへは指示・直近の会話・チャンネル一覧・録画シリーズ名の一部・検索結果が送られます。音声ファイルはWhisperだけに送ります。
- 無音などで認識文字列が空の場合は、会話AIを呼び出しません。
- WAV作成の完了を待ってから送信し、送信前に読み込んだ一時ファイルは削除します。
- 会話AI専用のHTTPクライアントを使用し、EPGStation等のURL書換え・認証設定は共有しません。
- 応答の思考部分に含まれる操作タグや、途中で切れた応答は実行しません。検索結果を報告するターンでは、操作や追加検索を実行しません。
- 既存のAI録画検索は直近20件を対象にしています。録画一覧画面の全件検索とは範囲が異なります。

## 個人ビルドの初期値

Git管理外の `local.properties` に以下のキーを指定すると、接続先を初期設定したAPKを作れます。設定画面から保存した値が優先されます。接続先はAPKに含まれるため、このAPKの配布先には注意してください。トークンをビルド初期値に含める機能はありません。

```properties
local.ai.baseUrl=http://YOUR_LLM_HOST:8001/v1
local.ai.model=YOUR_MODEL_ID
local.whisper.baseUrl=http://YOUR_WHISPER_HOST:3081/v1
local.whisper.model=whisper-local
```

## 検証

`./gradlew :app:testDebugUnitTest :app:assembleRelease`

`LocalAiClientTest` は模擬HTTPサーバーでmultipart形式、認証先の分離、URL結合、モデル指定、JSON形式、無音、不正音声、HTTP失敗、途中応答・思考タグの処理を確認します。

`LocalAiIntegrationTest` は通常スキップされます。明示的に以下の環境変数を指定したときだけ、合成音声をWhisperへ送信し、認識結果をLLMへ渡して操作タグの応答を確認します。TVの操作は実行しません。

- `KOMOREBI_TEST_WAV`: 「BS11にしてください」の合成音声WAVのパス
- `KOMOREBI_TEST_WHISPER_URL`: テスト対象WhisperのURL
- `KOMOREBI_TEST_AI_URL`: テスト対象LLMのURL
- `KOMOREBI_TEST_AI_MODEL`: テスト対象モデル名

API仕様: [Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[Transcriptions](https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create)
