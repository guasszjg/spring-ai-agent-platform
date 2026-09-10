package com.example.agentplatform.service;

import com.example.agentplatform.model.AppUser;
import com.example.agentplatform.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OwnerNameResolver {

    private final UserRepository userRepository;

    public OwnerNameResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Map<String, String> usernames(Collection<String> ownerIds) {
        Map<String, String> map = new HashMap<>();
        if (ownerIds == null || ownerIds.isEmpty()) {
            return map;
        }
        Set<String> ids = ownerIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return map;
        }
        for (AppUser user : userRepository.findAllById(ids)) {
            map.put(user.getId(), user.getUsername());
        }
        return map;
    }

    public static String lookup(Map<String, String> names, String ownerId) {
        if (names == null || ownerId == null || ownerId.isBlank()) {
            return null;
        }
        return names.get(ownerId);
    }

    public String username(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return null;
        }
        return userRepository.findById(ownerId).map(AppUser::getUsername).orElse(null);
    }

    public static boolean matchesOwner(String ownerId, String filterOwnerId) {
        if (filterOwnerId == null || filterOwnerId.isBlank()) {
            return true;
        }
        return filterOwnerId.equals(ownerId);
    }
}
