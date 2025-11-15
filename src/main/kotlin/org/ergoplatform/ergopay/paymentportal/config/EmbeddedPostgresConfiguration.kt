package org.ergoplatform.ergopay.paymentportal.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import javax.sql.DataSource

@Configuration
class EmbeddedPostgresConfiguration(
    private val embeddedPostgresManager: EmbeddedPostgresManager
) {
    @Bean
    @Primary
    fun dataSource(): DataSource = embeddedPostgresManager.dataSource()
}
