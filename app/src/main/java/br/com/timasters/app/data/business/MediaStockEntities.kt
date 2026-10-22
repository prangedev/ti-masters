package br.com.timasters.app.data.business

import androidx.annotation.StringRes
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import br.com.timasters.app.R
import java.util.UUID

/*
 * Business part 3 tables. Same sync fields as the other Business tables (id, updatedAt,
 * serverUpdatedAt, isDirty, deletedAt).
 */

enum class PhotoStage(val serverId: String, @param:StringRes val labelResId: Int) {
    BEFORE("antes", R.string.photo_stage_before),
    AFTER("depois", R.string.photo_stage_after);

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.serverId == value } ?: BEFORE
    }
}

/** A photo of a quote / service order; the JPEG is kept in the app's private storage. */
@Entity(tableName = "order_photos", indices = [Index("companyId"), Index("orderId"), Index("isDirty")])
data class OrderPhotoEntity(
    @PrimaryKey val id: String,
    val companyId: String,
    val orderId: String,
    val stage: String,
    /** "<company>/<order>/<id>.jpg" once uploaded. */
    val storagePath: String? = null,
    /** Local only: the JPEG still has to be uploaded. */
    val pendingUpload: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val serverUpdatedAt: String? = null,
    val isDirty: Boolean = true,
    val deletedAt: Long? = null
) {
    val photoStage: PhotoStage get() = PhotoStage.from(stage)
}

enum class StockMovementKind(val serverId: String, @param:StringRes val labelResId: Int) {
    IN("entrada", R.string.stock_kind_in),
    OUT("saida", R.string.stock_kind_out),
    ADJUSTMENT("ajuste", R.string.stock_kind_adjustment),
    /** Parts used by a concluded service order (created and removed automatically). */
    SERVICE_ORDER("os", R.string.stock_kind_service_order);

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.serverId == value } ?: ADJUSTMENT
    }
}

/** One change of a product's quantity; the stock is the sum of the active movements. */
@Entity(tableName = "stock_movements", indices = [Index("companyId"), Index("itemId"), Index("orderId"), Index("isDirty")])
data class StockMovementEntity(
    @PrimaryKey val id: String,
    val companyId: String,
    val itemId: String,
    val orderId: String? = null,
    val kind: String,
    /** Positive adds to the stock, negative removes. */
    val quantity: Double,
    val unitCostCents: Long = 0,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val serverUpdatedAt: String? = null,
    val isDirty: Boolean = true,
    val deletedAt: Long? = null
) {
    val movementKind: StockMovementKind get() = StockMovementKind.from(kind)

    companion object {
        /** Same id on every phone for "parts of order X, product Y", so they are counted once. */
        fun orderMovementId(orderId: String, itemId: String): String =
            UUID.nameUUIDFromBytes("os-stock:$orderId:$itemId".toByteArray()).toString()
    }
}

/** A stock-controlled product with its current quantity. */
data class StockLevel(
    @Embedded val item: ServiceItemEntity,
    val quantity: Double
) {
    /** At or below the minimum (or empty when no minimum was set). */
    val isLow: Boolean get() = if (item.minStock > 0) quantity <= item.minStock else quantity <= 0
}

/** A movement with the number of the service order that caused it, for the history. */
data class StockMovementWithOrder(
    @Embedded val movement: StockMovementEntity,
    val orderNumber: Int?
)
