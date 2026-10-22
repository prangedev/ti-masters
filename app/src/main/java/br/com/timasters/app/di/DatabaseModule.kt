package br.com.timasters.app.di

import android.content.Context
import androidx.room.Room
import br.com.timasters.app.data.business.AppointmentDao
import br.com.timasters.app.data.business.ClientDao
import br.com.timasters.app.data.business.CompanyProfileDao
import br.com.timasters.app.data.business.OrderPhotoDao
import br.com.timasters.app.data.business.PricingSettingsDao
import br.com.timasters.app.data.business.ServiceItemDao
import br.com.timasters.app.data.business.ServiceOrderDao
import br.com.timasters.app.data.business.StockMovementDao
import br.com.timasters.app.data.business.TicketDao
import br.com.timasters.app.data.business.TimeEntryDao
import br.com.timasters.app.data.local.AppDatabase
import br.com.timasters.app.data.speedtest.SpeedTestDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "ti_masters_database")
            // Versions 1-2 were development prototypes without user data. Later versions must
            // provide migrations: a missing one makes the app fail loudly instead of erasing data.
            .fallbackToDestructiveMigrationFrom(true, 1, 2)
            .build()

    @Provides
    fun provideClientDao(db: AppDatabase): ClientDao = db.clientDao()

    @Provides
    fun provideTicketDao(db: AppDatabase): TicketDao = db.ticketDao()

    @Provides
    fun provideAppointmentDao(db: AppDatabase): AppointmentDao = db.appointmentDao()

    @Provides
    fun provideTimeEntryDao(db: AppDatabase): TimeEntryDao = db.timeEntryDao()

    @Provides
    fun provideServiceItemDao(db: AppDatabase): ServiceItemDao = db.serviceItemDao()

    @Provides
    fun providePricingSettingsDao(db: AppDatabase): PricingSettingsDao = db.pricingSettingsDao()

    @Provides
    fun provideServiceOrderDao(db: AppDatabase): ServiceOrderDao = db.serviceOrderDao()

    @Provides
    fun provideCompanyProfileDao(db: AppDatabase): CompanyProfileDao = db.companyProfileDao()

    @Provides
    fun provideOrderPhotoDao(db: AppDatabase): OrderPhotoDao = db.orderPhotoDao()

    @Provides
    fun provideStockMovementDao(db: AppDatabase): StockMovementDao = db.stockMovementDao()

    @Provides
    fun provideSpeedTestDao(db: AppDatabase): SpeedTestDao = db.speedTestDao()
}
