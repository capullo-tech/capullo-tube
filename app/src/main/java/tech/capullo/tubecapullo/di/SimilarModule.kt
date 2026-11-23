package tech.capullo.tubecapullo.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tech.capullo.tubecapullo.data.similar.LastfmSimilarFinder
import tech.capullo.tubecapullo.data.similar.SimilarFinder

@Module
@InstallIn(SingletonComponent::class)
abstract class SimilarModule {
    @Binds
    abstract fun bindSimilarFinder(finder: LastfmSimilarFinder): SimilarFinder
}
