# TypeSome

一个带语音输入的安卓拼音输入法，灰色扁平皮肤。长按空格说话，手指不松开往上滑可以选择三种模式：

| 模式 | 做什么 |
| --- | --- |
| 语音输入 | 直接把说的话转成文字 |
| 智能整理 | 识别后交给大模型，去掉口水词、改口和重复，长内容自动分点 |
| 翻译 | 说中文，输出目标语言（默认英语，设置里可改） |

滑到左下角「取消」再松手会放弃这次录音。也可以点键盘顶部的话筒图标开始说话，说完点模式卡片结束。

## 功能

- 拼音全键（全拼、简拼如 `zg`→中国、整句输入、模糊音、常见错拼纠正如 `ign`→`ing`）
- 点候选栏上方的拼音可以把光标移到输错的位置修改，旁边的 ◀ ▶ 也能移动光标
- 拼音九键（左栏可选拼音组合，「分词」键，上滑输入数字）
- 手写输入（Google ML Kit，首次使用联网下载约 20MB 模型，需能访问 Google，之后离线可用）
- 英文全键、九宫格数字键盘、符号面板、Emoji/颜文字、文字编辑面板（方向键、选择、全选/复制/粘贴）、剪贴板历史
- 用户词库：自动记住选过的词，可在设置里查看、删除、清空、手动添加，导出成文本文件换手机后再导入
- 通讯录：输入联系人姓名后，候选栏直接给出他的手机号
- 验证码：收到验证码后候选栏显示「验证码 123456」一键填入。来源有通知（推荐，需在设置里开「允许读取通知」）、短信（需短信权限）和剪贴板
- 计算：输入 `1+1` 等算式后，候选栏给出 `2` 和 `1+1=2`
- 空格左右滑动移动光标

## 语音识别

- **在线（默认）**：OpenAI 兼容的 `/audio/transcriptions` 接口。默认填的是硅基流动 `https://api.siliconflow.cn/v1` + `FunAudioLLM/SenseVoiceSmall`，只需在设置里填 API Key；也可以换成 OpenAI `whisper-1`、Groq 等。
- **离线**：在设置里下载模型后完全在手机上识别，基于 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)。可选 SenseVoice（中文推荐）、Whisper base、Whisper small。

大模型（整理/翻译）走 OpenAI 兼容的 `/chat/completions`，默认同样是硅基流动，Key 留空时沿用语音识别的 Key。两个提示词都可以在设置里直接改。

## 编译

推到 `main` 后 GitHub Actions 会自动编译，APK 在仓库的 Releases 页面（也在 Actions 运行记录的 Artifacts 里）。

本地编译：

```bash
python3 tools/gen_dict.py        # 下载 rime-ice 词库并生成 app/src/main/assets/pinyin_dict.txt
./gradlew assembleRelease
```

## 安装后

1. 打开「TypeSome」App，点「启用输入法」并打开开关，再点「切换为当前」。
2. 授予麦克风权限（语音输入必需）；想用对应功能再授予通讯录、短信权限。
3. 在「语音识别」里填 API Key，或者切到离线模型并下载一个。

## 说明

- 拼音词库在编译时从 [rime-ice（雾凇拼音）](https://github.com/iDvel/rime-ice) 生成（GPL-3.0），不随仓库保存。
- 签名用的是仓库里的 `app/voiceime.keystore`（密码在 `app/build.gradle.kts`），方便每次新版本直接覆盖安装。如果打算公开发布，请换成自己的密钥并通过环境变量 `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 传入。
- 小米、OPPO、vivo、华为等系统有「验证码保护」，会挡住第三方 App 读取验证码短信。这些厂商没有开放给输入法的验证码接口，所以这里改为读取通知；如仍不行，请在系统短信 App 设置里关闭验证码保护。
- 暂未实现：笔画、五笔、英文九键、更多语言。
