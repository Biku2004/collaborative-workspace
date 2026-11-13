package com.example.collaborativeworkspace.collaboration.cluster;

import java.util.UUID;

/** Emitted only for locally authenticated presence; remote observations never re-publish it. */
public record LocalPresence(UUID workspace, UUID document, UUID user, UUID presence, String frame) {}
