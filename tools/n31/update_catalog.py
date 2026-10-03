"""N31 authored feature descriptions; no runtime or remote translation."""
from pathlib import Path
import json, subprocess, sys
R=Path(__file__).resolve().parents[2]
p=R/'localization/catalog.json';d=json.loads(p.read_text(encoding='utf-8'))
rows={
'en':("Add the selected languages to YouTube’s ‘Auto-translate’ language list. You can select multiple languages.","When enabled, choose a language in YouTube’s ‘Auto-translate’ list to translate captions in real time using your configured AI service."),
'zh-rCN':("将所选语言添加到 YouTube 的‘自动翻译’语言列表，可同时选择多种语言。","启用后，在 YouTube 的‘自动翻译’列表中选语言，使用已配置的 AI 服务实时翻译字幕。"),
'zh-rTW':("將所選語言加入 YouTube 的「自動翻譯」語言清單，可同時選取多種語言。","啟用後，在 YouTube 的「自動翻譯」清單中選取語言，使用已設定的 AI 服務即時翻譯字幕。"),
'es':("Añade los idiomas seleccionados a la lista «Traducir automáticamente» de YouTube. Puedes seleccionar varios idiomas.","Al activar esta función, elige un idioma en la lista «Traducir automáticamente» de YouTube para traducir los subtítulos en tiempo real con el servicio de IA configurado."),
'fr':("Ajoutez les langues sélectionnées à la liste « Traduire automatiquement » de YouTube. Vous pouvez sélectionner plusieurs langues.","Une fois la fonction activée, choisissez une langue dans la liste « Traduire automatiquement » de YouTube pour traduire les sous-titres en temps réel avec le service d’IA configuré."),
'de':("Füge die ausgewählten Sprachen zur YouTube-Liste „Automatisch übersetzen“ hinzu. Du kannst mehrere Sprachen auswählen.","Wähle nach dem Aktivieren eine Sprache in der YouTube-Liste „Automatisch übersetzen“, um Untertitel mit deinem eingerichteten KI-Dienst in Echtzeit zu übersetzen."),
'pt':("Adicione os idiomas selecionados à lista «Traduzir automaticamente» do YouTube. Pode selecionar vários idiomas.","Depois de ativar, escolha um idioma na lista «Traduzir automaticamente» do YouTube para traduzir as legendas em tempo real com o serviço de IA configurado."),
'ru':("Добавьте выбранные языки в список YouTube «Автоматический перевод». Можно выбрать несколько языков.","После включения выберите язык в списке YouTube «Автоматический перевод», чтобы переводить субтитры в реальном времени с помощью настроенного сервиса ИИ."),
'ja':("選択した言語を YouTube の「自動翻訳」の言語リストに追加します。複数の言語を選択できます。","有効にすると、YouTube の「自動翻訳」リストで言語を選び、設定済みの AI サービスで字幕をリアルタイムに翻訳できます。"),
'ko':("선택한 언어를 YouTube의 ‘자동 번역’ 언어 목록에 추가합니다. 여러 언어를 동시에 선택할 수 있습니다.","활성화한 후 YouTube의 ‘자동 번역’ 목록에서 언어를 선택하면 설정된 AI 서비스로 자막을 실시간 번역합니다."),
'ar':("أضف اللغات المحددة إلى قائمة «الترجمة التلقائية» في YouTube. يمكنك اختيار عدة لغات.","بعد التفعيل، اختر لغة من قائمة «الترجمة التلقائية» في YouTube لترجمة الترجمة النصية فورًا باستخدام خدمة الذكاء الاصطناعي التي أعددتها."),
'hi':("चुनी गई भाषाओं को YouTube की ‘अपने-आप अनुवाद करें’ भाषा सूची में जोड़ें। आप एक साथ कई भाषाएँ चुन सकते हैं।","सक्षम करने के बाद, YouTube की ‘अपने-आप अनुवाद करें’ सूची में भाषा चुनें। आपकी कॉन्फ़िगर की गई AI सेवा सबटाइटल का रीयल-टाइम अनुवाद करेगी।"),
'id':("Tambahkan bahasa yang dipilih ke daftar bahasa ‘Terjemahkan otomatis’ YouTube. Anda dapat memilih beberapa bahasa.","Setelah diaktifkan, pilih bahasa dalam daftar ‘Terjemahkan otomatis’ YouTube untuk menerjemahkan subtitel secara real time menggunakan layanan AI yang telah dikonfigurasi."),
'vi':("Thêm các ngôn ngữ đã chọn vào danh sách ‘Dịch tự động’ của YouTube. Bạn có thể chọn nhiều ngôn ngữ cùng lúc.","Sau khi bật, hãy chọn ngôn ngữ trong danh sách ‘Dịch tự động’ của YouTube để dịch phụ đề theo thời gian thực bằng dịch vụ AI đã cấu hình.")}
for locale,(languages,ai) in rows.items():
    d['languages'][locale]['languages_summary']=languages
    d['languages'][locale]['ai_summary']=ai
errors={
    'en':'The model ID cannot be empty', 'zh-rCN':'模型 ID 不能为空', 'zh-rTW':'模型 ID 不可留空',
    'es':'El ID del modelo no puede estar vacío', 'fr':'L’identifiant du modèle ne peut pas être vide',
    'de':'Die Modell-ID darf nicht leer sein', 'pt':'O ID do modelo não pode estar vazio',
    'ru':'Идентификатор модели не может быть пустым', 'ja':'モデル ID を入力してください',
    'ko':'모델 ID를 입력하세요', 'ar':'لا يمكن ترك معرّف النموذج فارغًا',
    'hi':'मॉडल ID खाली नहीं हो सकता', 'id':'ID model tidak boleh kosong', 'vi':'ID mô hình không được để trống'
}
for locale,value in errors.items():d['languages'][locale]['model_empty']=value
addresses={
'en':'Enter a valid HTTP(S) OpenAI-compatible base URL without embedded credentials or a fragment.',
'zh-rCN':'请输入有效的 HTTP(S) OpenAI 兼容接口地址，不要包含用户名、密码或片段标记。',
'zh-rTW':'請輸入有效的 HTTP(S) OpenAI 相容介面網址，不要包含使用者名稱、密碼或片段標記。',
'es':'Introduce una URL base HTTP(S) válida y compatible con OpenAI, sin credenciales integradas ni fragmentos.',
'fr':'Saisissez une URL de base HTTP(S) valide et compatible avec OpenAI, sans identifiants intégrés ni fragment.',
'de':'Gib eine gültige OpenAI-kompatible HTTP(S)-Basis-URL ohne eingebettete Zugangsdaten oder Fragment ein.',
'pt':'Introduza um URL base HTTP(S) válido e compatível com OpenAI, sem credenciais incorporadas nem fragmentos.',
'ru':'Введите корректный базовый HTTP(S)-адрес, совместимый с OpenAI, без встроенных учётных данных и фрагмента.',
'ja':'有効な HTTP(S) の OpenAI 互換 API アドレスを入力してください。ユーザー名、パスワードやフラグメントは含めないでください。',
'ko':'인증 정보나 프래그먼트가 없는 유효한 HTTP(S) OpenAI 호환 기본 URL을 입력하세요.',
'ar':'أدخل عنوان HTTP(S) أساسيًا صالحًا ومتوافقًا مع OpenAI، دون بيانات اعتماد مضمنة أو جزء مرجعي.',
'hi':'मान्य HTTP(S) OpenAI-संगत बेस URL दर्ज करें। इसमें उपयोगकर्ता नाम, पासवर्ड या फ़्रैगमेंट न हो।',
'id':'Masukkan URL dasar HTTP(S) yang valid dan kompatibel dengan OpenAI, tanpa kredensial tertanam atau fragmen.',
'vi':'Nhập URL cơ sở HTTP(S) hợp lệ và tương thích với OpenAI, không chứa thông tin đăng nhập hoặc phần fragment.'}
for locale,value in addresses.items():d['languages'][locale]['api_address_invalid']=value
p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
subprocess.run([sys.executable,str(R/'tools/generate_localization.py')],check=True)
(R/'extensions/extension/src/test/resources/n30/localization-expected.json').write_text(json.dumps(d['languages'],ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
