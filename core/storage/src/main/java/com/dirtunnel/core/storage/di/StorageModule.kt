package com.dirtunnel.core.storage.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.dirtunnel.core.storage.dao.DirectoryDao
import com.dirtunnel.core.storage.dao.ForwardDao
import com.dirtunnel.core.storage.dao.FrpProfileDao
import com.dirtunnel.core.storage.dao.LogDao
import com.dirtunnel.core.storage.dao.ServiceDao
import com.dirtunnel.core.storage.dao.TemplateDao
import com.dirtunnel.core.storage.data.AppDatabase
import com.dirtunnel.core.storage.data.SettingsStore
import com.dirtunnel.core.storage.data.dirtunnelSettingsDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.build(context)

    @Provides fun provideDirectoryDao(db: AppDatabase): DirectoryDao = db.directoryDao()
    @Provides fun provideServiceDao(db: AppDatabase): ServiceDao = db.serviceDao()
    @Provides fun provideTemplateDao(db: AppDatabase): TemplateDao = db.templateDao()
    @Provides fun provideFrpProfileDao(db: AppDatabase): FrpProfileDao = db.frpProfileDao()
    @Provides fun provideForwardDao(db: AppDatabase): ForwardDao = db.forwardDao()
    @Provides fun provideLogDao(db: AppDatabase): LogDao = db.logDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dirtunnelSettingsDataStore

    @Provides
    @Singleton
    fun provideSettingsStore(dataStore: DataStore<Preferences>): SettingsStore =
        SettingsStore(dataStore)
}
