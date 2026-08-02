package com.thinktank.recorder.cloudsummary

import com.thinktank.recorder.ondevice.api.RemoteSummaryGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CloudSummaryModule {
    @Binds
    @Singleton
    abstract fun remoteSummaryGateway(service: OAuthCloudSummaryService): RemoteSummaryGateway

    @Binds
    @Singleton
    abstract fun oauthAccountController(service: OAuthCloudSummaryService): OAuthAccountController
}
