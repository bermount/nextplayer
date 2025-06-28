package dev.anilbeesetti.nextplayer.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent // Or relevant component (e.g., ActivityRetainedComponent)
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.data.repository.fake.FakePreferencesRepository // Your fake implementation
import javax.inject.Singleton // Match the scope of your implementation

@Module
@InstallIn(SingletonComponent::class) // This module will provide dependencies at the application level
abstract class DataRepositoryModule { // Use 'abstract class' with @Binds

    @Binds
    @Singleton // Match the scope of FakePreferencesRepository if it's Singleton
    abstract fun bindPreferencesRepository(
        fakePreferencesRepository: FakePreferencesRepository
    ): PreferencesRepository
}
