# R_AND_D_NOTES — 01_API_UX_Dept
# append-only，絕對不得修改或刪除舊行

| TIMESTAMP | TOPIC | HYPOTHESIS | EXPERIMENT | RESULT | NEXT_STEP |
|-----------|-------|------------|------------|--------|-----------|
| 2026-04-04 00:09:56 | QEF Solver Eigenvector Algorithm | 原本的 Cardano cubic root 分析解雖然在尋找 Eigenvalues 時速度快，但在處理 Eigenvectors 時依賴外積，在有重複特徵值或數值不穩定的矩陣時會計算出長度為 0 的錯誤向量，導致後續 QEF 解答噴飛。 | 寫了一個小腳本分離並測試了 `jacobiEigen3x3`，驗證在對角線為 0/1/1 的矩陣時原算法會回傳全 0 的 eigenvectors。 | 將算法換回標準且數值穩定的 Jacobi 旋轉演算法後，順利計算出正確的投影，並通過測試。 | 在尋找效能瓶頸時，應更注意代數算法在 Edge cases (如 degenerate planes) 時的數值穩定性。 |
