package com.dream.shouna.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dream.shouna.domain.model.ItemStatus

/**
 * `item` 表（ARCHITECTURE-P0 §3.1 / §3.2）：一物一处，位置必填、状态落库。
 *
 * 外键取向：
 * - `location_id` → `location.id` **ON DELETE RESTRICT**：孤儿防线（§3.4 不变量 1）。
 *   删除位置必须先迁移／标记其下全部物品，DB 层不允许留下指向已删位置的记录。
 * - `category_id` → `category.id` **ON DELETE SET NULL**：分类可删，物品回落「未分类」。
 *
 * `status` 由 [com.dream.shouna.data.local.Converters] 以稳定 code 存取（§3.2）。
 */
@Entity(
    tableName = "item",
    foreignKeys = [
        ForeignKey(
            entity = LocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["location_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["location_id"]),
        Index(value = ["category_id"]),
        Index(value = ["normalized_name"]),
    ],
)
data class ItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    /** 名称，允许同名；身份以 `id` 判定，绝不靠名称。 */
    @ColumnInfo(name = "name")
    val name: String,
    /** 归一化名称（全角→半角、大小写、空白折叠），检索用。 */
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,
    /** FR-20：名称全拼（如 `dianfengshan`）。 */
    @ColumnInfo(name = "pinyin_full")
    val pinyinFull: String,
    /** FR-20：名称拼音首字母（如 `dfs`）。 */
    @ColumnInfo(name = "pinyin_initial")
    val pinyinInitial: String,
    /** 仍恒空串：别名属 F2（P-ITEM-EDIT），本页不接。 */
    @ColumnInfo(name = "alias_blob")
    val aliasBlob: String,
    /** 位置归属，**必填**（位置必填口径）；FK RESTRICT。 */
    @ColumnInfo(name = "location_id")
    val locationId: String,
    /** 未分类 = NULL。 */
    @ColumnInfo(name = "category_id")
    val categoryId: String?,
    /** `in_storage` / `to_be_put_back` / `gone`。 */
    @ColumnInfo(name = "status")
    val status: ItemStatus,
    /** 恒 1（数量属 F2）。 */
    @ColumnInfo(name = "quantity")
    val quantity: Int,
    /** 可选备注。 */
    @ColumnInfo(name = "note")
    val note: String?,
    /** epoch millis UTC。 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    /** 归属或状态变动才刷新（§3.5）。 */
    @ColumnInfo(name = "last_modified_at")
    val lastModifiedAt: Long,
    /** 「确认还在」写入；未确认 = NULL。 */
    @ColumnInfo(name = "last_confirmed_at")
    val lastConfirmedAt: Long?,
)
