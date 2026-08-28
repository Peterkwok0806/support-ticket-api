# Project Instructions

## Required documentation

開始任何任務前，必須閱讀：

- 專案概覽：`docs/overview.md`
- 與任務相關的架構文件：`docs/architecture/`
- 與任務相關的需求文件：`docs/requirements/`

請只閱讀與目前任務相關的文件，不要無差別讀取整個文件樹。

若找不到對應規格，或規格不足以支持實作，必須先詢問使用者，不可自行建立商業規則。

---

## Task scope

每次只處理以下其中一項：

- 一個功能
- 一個明確的 bug 修正
- 一個明確的重構任務

若使用者提出多個功能，必須拆成多個任務，並先請使用者選擇本次要處理的項目。

不得順手處理其他問題。

---

## Required workflow

### Phase 1: Understand

開始修改前，必須：

1. 閱讀必要文件。
2. 閱讀相關程式碼。
3. 說明對需求的理解。
4. 列出以下內容：
   - In scope：本次允許處理的內容
   - Out of scope：本次不處理的內容
   - Expected files：預計修改的檔案
   - Acceptance criteria：完成條件
5. 提出最小實作計畫。
6. 等待使用者明確確認。

在使用者確認前：

- 不得修改程式碼。
- 不得新增檔案。
- 不得執行會改變專案狀態的操作。

### Phase 2: Implement

使用者確認後：

1. 僅依照已確認的計畫實作。
2. 優先修改最少必要檔案。
3. 優先重用既有程式碼。
4. 遵守現有命名、格式、架構和錯誤處理方式。
5. 不新增不必要的抽象層。
6. 不進行計畫外重構。

### Phase 3: Verify

完成實作後：

1. 執行與本次任務相關的測試。
2. 執行專案既有的 build、lint 或 type check 指令。
3. 檢查 `git diff`。
4. 確認沒有修改 Out of scope 內容。
5. 對照 Acceptance criteria 逐項確認。

---

## Stop conditions

遇到以下情況時，必須停止並詢問使用者：

- 找不到相關需求或架構文件。
- 規格、程式碼與使用者指示互相衝突。
- 需求有多種合理解釋。
- 需要修改 Expected files 以外的檔案。
- 需要新增套件或修改套件版本。
- 需要修改公開 API。
- 需要修改資料庫 schema 或資料格式。
- 需要改變既有架構或依賴方向。
- 測試失敗，但原因可能與本次任務無關。
- 發現其他問題但不屬於本次任務。

停止時請回報：

1. 發現的問題。
2. 為什麼需要擴大範圍或補充資訊。
3. 可行的處理選項。
4. 等待使用者決定。

---

## Architecture and specification rules

- 規格文件優先於一般慣例和個人推測。
- 遵守既有分層、依賴方向和設計模式。
- 優先使用現有 service、repository、component 和 utility。
- 不得自行新增未定義的商業規則。
- 不得自行決定未定義的預設值、權限、錯誤行為或 API 格式。
- 如果規格不完整，先詢問，不要猜測。

---

## Final report

完成後使用以下格式：

### Implemented

- 實作內容

### Files changed

- 檔案名稱：修改原因

### Verification

- 執行的測試：
- Build 結果：
- Lint 或 type check 結果：
- Git diff 檢查結果：

### Acceptance criteria

- [x] 條件一
- [ ] 條件二，以及未完成原因

### Out of scope

- 本次刻意沒有處理的內容

### Remaining risks

- 尚未確認或可能需要後續處理的問題