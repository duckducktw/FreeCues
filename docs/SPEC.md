# FreeCues — 行為規格（Behavioural spec）

本文件描述車輛動態提示點的行為與參數，作為實作依據；不含任何第三方原始碼。
實作見 `src/`。

## 架構
- 前景 Service（`MovementDetectionService`）註冊感測器 → 平滑 → 位移量 → 推給全螢幕覆蓋層 View。
- 覆蓋層：`TYPE_APPLICATION_OVERLAY`(2038)、全螢幕、不可觸控/不可聚焦（觸控穿透）；
  Android 12+ 視窗 alpha 設為 `InputManager.getMaximumObscuringOpacityForTouch()`（否則系統擋觸控）。
- 開關入口：App 主畫面、通知列動作（暫停/設定）、Quick Settings Tile。
- 其餘大量程式碼＝廣告 SDK（AppLovin/Mintegral/Pangle/Unity/FB）＋ Billing ＋ Firebase。

## 感測器（取樣週期 10 000 µs ≈ 100 Hz）
| 模式 | 感測器 | 用途 |
|---|---|---|
| 轉向 | ROTATION_VECTOR(15) → getOrientation → yaw(°) | 水平位移 |
| 加減速（預設，若有）| LINEAR_ACCELERATION(10) | 前後/左右 |
| 加減速（替代/無線性加速時）| GYROSCOPE(4) | 前後/左右 |

### 平滑
- 簡單滑動平均（每軸 FIFO），Service 內窗口 **15**、全域另一份 **30**。

### 轉向（yaw）
- `d = ((yaw - prevYaw) + 360) % 360`，>180 則 −360。
- `|d| > 0.15°` 才觸發；位移量 = `|d| × 4`；d<0 / d>0 分別推往左/右。

### 線性加速模式
- `x' = ax × 0.8`，`y' = (1 − min(|az|,10)/10) × 0.8 × ay`（垂直方向顛簸會壓低前後量）。
- 門檻 **0.25 m/s²**。
- 交叉軸抑制：某軸輸出 × `max(0.05, 1/(1 + 0.8·|另一軸|))`（另一軸 ≤0.25 時為 1）。

### 陀螺儀模式
- 直接用平滑後 (x,y)：垂直 = y、水平 = −x，門檻 **0.15 rad/s**，無交叉抑制。

### 方向映射
- 四個方向（上/下/左/右）依 `Display.getRotation()` (0–3) 旋轉對應，橫豎屏皆正確。
- CONFIGURATION_CHANGED 時更新 rotation。

### 位移量
- `px = value × sensitivity`；sensitivity：低 **1.8**、高 **2.7**（預設高）。
- 每次事件位移上限 **15 px**。

## 視覺樣式
### 樣式 1：邊緣點陣（預設，仿 iOS Vehicle Motion Cues）
- 左右兩側各 4 欄（共 8 欄），欄距 **48 dp**，同欄點距 **170 dp**，奇數欄錯開半個點距。
- 點半徑 = `min(min(w,h)/75, 7 dp)`。
- 點移出畫面後循環到另一端（無限捲動）。
- 停止移動 200 ms 後做 200 ms 淡出；出現時 1000 ms 淡入。
### 樣式 2：散點氣泡
- 30 顆，隨機位置，半徑 7 dp，產生時嘗試 ≤10 次避免重疊。
- 每次移動推進生命週期：前半段每步 scale +0.022（上限 1.5）、後半段 −0.022；
  壽命 50 步，結束即在隨機位置重生。
- 移出畫面即移除並重生。停止 500 ms 後淡出。

### 點的配色
- 雙層圓：外圈半徑 r，內圈 0.85r。
- 預設：外 `#2C2C2C`、內 `#FFFFFF`；另一組外 `#212121` 系深色。
- 自訂色：內圈＝使用者色；外圈依亮度 `0.2126R+0.7152G+0.0722B` 自動取 `#2C2C2C`/`#ECECEC` 對比色。
- 可調：點大小倍率、透明度、顏色、樣式、靈敏度、感測器模式。

## 付費牆（不實作）
- 免費版每次使用需看廣告；永久版內購。淨室版全部功能免費、無廣告、無網路權限。

## 主畫面 UI（main_activity.xml）
- 白底 ConstraintLayout；正中 180dp 圓形按鈕 `#292929`（start_btn），內含白色播放三角 / 暫停雙槓。
- 左上 18sp App 名稱；右上 48dp 齒輪（`#292929`）。
- 點齒輪：齒輪 rotation 0→90° 並換成 ×，按鈕 alpha→0 / translationY +50dp，
  設定面板 alpha 0→1、scale 0.5→1，皆 300ms AccelerateDecelerate。
- 設定面板：5 組「32sp 標題 + 18sp roboto_light 分頁（TabLayout，底線指示、非全寬）」
  - Sensitivity：Low / Standard（1.8 / 2.7）
  - Visibility：Always / Only in Vehicle
  - Color：White / Black / Pick（Pick 開格狀選色器）
  - Style：Grid / Bubble
  - Size：Small / Standard / Large（0.8 / 1.0 / 1.8）
- 按下開始：按鈕 scale 1.0→0.4→1.0 彈跳（400/800ms）。
- App 本身字串只有英文（各語系 split 僅含系統庫字串）。
