/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
/**
 * A small sample application for {@code GeneratedProcessorPersistenceTest}: three {@code PersistentNode}s (membership,
 * front desk, bookings) on one {@code appState} cache, each naming the cache with an overridden
 * {@code @ServiceRegistered} method and saving through the inherited {@code @AfterEvent}.
 * {@link com.telamin.mongoose.plugin.testsupport.gym.generated.GymProcessor} is the processor the Fluxtion AOT
 * generator produced for this graph (membership; frontDesk and bookings, each taking membership; event audit at INFO),
 * checked in so this build needs no generator. Regenerate it if a node's annotations change.
 */
package com.telamin.mongoose.plugin.testsupport.gym;
