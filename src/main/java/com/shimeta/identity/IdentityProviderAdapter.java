package com.shimeta.identity;

import com.shimeta.model.AppUser;
import com.shimeta.model.ExternalIdentity;
import com.shimeta.model.IdentityProvider;

public interface IdentityProviderAdapter {
    IdentitySyncResult createRemote(AppUser user, IdentityProvider provider);
    IdentitySyncResult bind(AppUser user, IdentityProvider provider, String externalId);
    IdentitySyncResult unbind(ExternalIdentity identity, IdentityProvider provider);
    IdentitySyncResult query(ExternalIdentity identity, IdentityProvider provider);
    IdentitySyncResult suspend(ExternalIdentity identity, IdentityProvider provider);
}
