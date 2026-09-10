package com.example.agentplatform.identity;

import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.model.ExternalIdentity;
import com.example.agentplatform.model.IdentityProvider;

public interface IdentityProviderAdapter {
    IdentitySyncResult createRemote(AppUser user, IdentityProvider provider);
    IdentitySyncResult bind(AppUser user, IdentityProvider provider, String externalId);
    IdentitySyncResult unbind(ExternalIdentity identity, IdentityProvider provider);
    IdentitySyncResult query(ExternalIdentity identity, IdentityProvider provider);
    IdentitySyncResult suspend(ExternalIdentity identity, IdentityProvider provider);
}
