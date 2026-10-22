package br.com.timasters.app.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import br.com.timasters.app.data.business.AppointmentDao
import br.com.timasters.app.data.business.AppointmentEntity
import br.com.timasters.app.data.business.ClientDao
import br.com.timasters.app.data.business.ClientEntity
import br.com.timasters.app.data.business.CompanyProfileDao
import br.com.timasters.app.data.business.CompanyProfileEntity
import br.com.timasters.app.data.business.PricingSettingsDao
import br.com.timasters.app.data.business.PricingSettingsEntity
import br.com.timasters.app.data.business.ServiceItemDao
import br.com.timasters.app.data.business.ServiceItemEntity
import br.com.timasters.app.data.business.ServiceOrderDao
import br.com.timasters.app.data.business.ServiceOrderEntity
import br.com.timasters.app.data.business.TicketDao
import br.com.timasters.app.data.business.TicketEntity
import br.com.timasters.app.data.business.TimeEntryDao
import br.com.timasters.app.data.business.TimeEntryEntity
import br.com.timasters.app.data.speedtest.SpeedTestDao
import br.com.timasters.app.data.speedtest.SpeedTestResult

/**
 * Version 3 is the first schema with real user data (Business, offline-first). From here on,
 * every schema change needs a Migration; the exported schemas live in app/schemas.
 * 3 → 4 (Business part 2) only adds tables, so Room generates it.
 */
@Database(
    entities = [
        ClientEntity::class,
        TicketEntity::class,
        AppointmentEntity::class,
        TimeEntryEntity::class,
        ServiceItemEntity::class,
        PricingSettingsEntity::class,
        ServiceOrderEntity::class,
        CompanyProfileEntity::class,
        SpeedTestResult::class
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 3, to = 4)]
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clientDao(): ClientDao
    abstract fun ticketDao(): TicketDao
    abstract fun appointmentDao(): AppointmentDao
    abstract fun timeEntryDao(): TimeEntryDao
    abstract fun serviceItemDao(): ServiceItemDao
    abstract fun pricingSettingsDao(): PricingSettingsDao
    abstract fun serviceOrderDao(): ServiceOrderDao
    abstract fun companyProfileDao(): CompanyProfileDao
    abstract fun speedTestDao(): SpeedTestDao
}
