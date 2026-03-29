package com.blockreality.api.item;

/**
 * 標記介面：表示此物品支援雕刻選區控制（chisel_sel_w, chisel_sel_h, chisel_erase）。
 *
 * API 層的 ChiselItem 與 fastdesign 層的 FdWandItem 都應實作此介面，
 * 以便 ChiselControlPacket 等跨模組邏輯能以型別安全方式判斷工具相容性，
 * 而非依賴脆弱的 class name 字串比對。
 *
 * @since 1.1.0
 */
public interface ChiselCompatibleTool {
    // Marker interface — no methods required
}
