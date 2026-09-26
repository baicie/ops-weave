package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.application.ModelSpendService;
import com.acme.opsweave.aicontrol.domain.ModelSpend;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.incident.application.IncidentService;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class ModelSpendConfiguration {
    @Bean ModelSpendService modelSpendService(AuthorizationService authorization,IncidentService incidents,InventoryWiring wiring,
        @Value("${opsweave.ai.model-provider:mock}") String provider,@Value("${opsweave.ai.model-name:}") String model,
        @Value("${opsweave.ai.price-version:}") String priceVersion,@Value("${opsweave.ai.input-micros-per-million:0}") long inputPrice,
        @Value("${opsweave.ai.output-micros-per-million:0}") long outputPrice,@Value("${opsweave.ai.max-call-micros:0}") long maxCall,
        @Value("${opsweave.ai.daily-micros:0}") long daily) {
        if(!provider.equals("mock") && !wiring.label().equals("postgres")) throw new IllegalStateException("Paid model admission requires PostgreSQL accounting");
        var policy=provider.equals("mock")?ModelSpend.Policy.mock():new ModelSpend.Policy(provider,model,priceVersion,inputPrice,outputPrice,maxCall,daily);
        return new ModelSpendService(authorization,incidents,wiring.toolReads(),wiring.modelSpend(),policy,Clock.systemUTC());
    }
}
