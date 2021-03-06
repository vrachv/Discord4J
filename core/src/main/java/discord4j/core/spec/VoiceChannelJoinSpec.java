/*
 * This file is part of Discord4J.
 *
 * Discord4J is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Discord4J is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Discord4J.  If not, see <http://www.gnu.org/licenses/>.
 */
package discord4j.core.spec;

import discord4j.common.LogUtil;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.VoiceServerUpdateEvent;
import discord4j.core.event.domain.VoiceStateUpdateEvent;
import discord4j.core.object.entity.channel.VoiceChannel;
import discord4j.discordjson.json.gateway.VoiceStateUpdate;
import discord4j.gateway.GatewayClientGroup;
import discord4j.gateway.json.ShardGatewayPayload;
import discord4j.rest.util.Permission;
import discord4j.rest.util.Snowflake;
import discord4j.voice.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.function.TupleUtils;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.util.Objects;

/**
 * Spec used to request a connection to a {@link VoiceChannel} and handle the initialization of the resulting
 * {@link VoiceConnection}.
 */
public class VoiceChannelJoinSpec implements Spec<Mono<VoiceConnection>> {

    /** The default maximum amount of time in seconds to wait before the connection to the voice channel timeouts. */
    private static final int DEFAULT_TIMEOUT = 5;

    private Duration timeout = Duration.ofSeconds(DEFAULT_TIMEOUT);
    private AudioProvider provider = AudioProvider.NO_OP;
    private AudioReceiver receiver = AudioReceiver.NO_OP;
    private VoiceSendTaskFactory sendTaskFactory = new LocalVoiceSendTaskFactory();
    private VoiceReceiveTaskFactory receiveTaskFactory = new LocalVoiceReceiveTaskFactory();
    private boolean selfDeaf;
    private boolean selfMute;

    private final GatewayDiscordClient gateway;
    private final VoiceChannel voiceChannel;

    public VoiceChannelJoinSpec(final GatewayDiscordClient gateway, final VoiceChannel voiceChannel) {
        this.gateway = Objects.requireNonNull(gateway);
        this.voiceChannel = voiceChannel;
    }

    /**
     * Configure the {@link AudioProvider} to use in the created {@link VoiceConnection}.
     *
     * @param provider Used to send audio.
     * @return This spec.
     */
    public VoiceChannelJoinSpec setProvider(final AudioProvider provider) {
        this.provider = provider;
        return this;
    }

    /**
     * Configure the {@link AudioReceiver} to use in the created {@link VoiceConnection}.
     *
     * @param receiver Used to receive audio.
     * @return This spec.
     * @deprecated Discord does not officially support bots receiving audio. It is not guaranteed that this
     * functionality works properly. Use at your own risk.
     */
    @Deprecated
    public VoiceChannelJoinSpec setReceiver(final AudioReceiver receiver) {
        this.receiver = receiver;
        return this;
    }

    /**
     * Configure the {@link VoiceSendTaskFactory} to use in the created {@link VoiceConnection}. A send task is created
     * when establishing a Voice Gateway session and is torn down when disconnecting.
     *
     * @param sendTaskFactory provides an audio send system that process outbound packets
     * @return this spec
     */
    public VoiceChannelJoinSpec setSendTaskFactory(VoiceSendTaskFactory sendTaskFactory) {
        this.sendTaskFactory = sendTaskFactory;
        return this;
    }

    /**
     * Configure the {@link VoiceReceiveTaskFactory} to use in the created {@link VoiceConnection}. A receive task is
     * created when establishing a Voice Gateway session and is torn down when disconnecting.
     *
     * @param receiveTaskFactory provides an audio receive system to process inbound packets
     * @return this spec
     * @deprecated Discord does not officially support bots receiving audio. It is not guaranteed that this
     * functionality works properly. Use at your own risk.
     */
    @Deprecated
    public VoiceChannelJoinSpec setReceiveTaskFactory(VoiceReceiveTaskFactory receiveTaskFactory) {
        this.receiveTaskFactory = receiveTaskFactory;
        return this;
    }

    /**
     * Sets whether to deafen this client when establishing a {@link VoiceConnection}.
     *
     * @param selfDeaf If this client is deafened.
     * @return This spec.
     */
    public VoiceChannelJoinSpec setSelfDeaf(final boolean selfDeaf) {
        this.selfDeaf = selfDeaf;
        return this;
    }

    /**
     * Sets whether to mute this client when establishing a {@link VoiceConnection}.
     *
     * @param selfMute If this client is muted.
     * @return This spec.
     */
    public VoiceChannelJoinSpec setSelfMute(final boolean selfMute) {
        this.selfMute = selfMute;
        return this;
    }

    /**
     * Sets the maximum amount of time to wait before the connection to the voice channel timeouts.
     * For example, the connection may get stuck when the bot does not have {@link Permission#VIEW_CHANNEL} or
     * when the voice channel is full.
     * The default value is {@value #DEFAULT_TIMEOUT} seconds.
     *
     * @param timeout The maximum amount of time to wait before the connection to the voice channel timeouts.
     * @return This spec.
     */
    public VoiceChannelJoinSpec setTimeout(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout);
        return this;
    }

    @Override
    public Mono<VoiceConnection> asRequest() {
        final long guildId = voiceChannel.getGuildId().asLong();
        final long channelId = voiceChannel.getId().asLong();
        final Flux<Long> selfIdSupplier = gateway.getGatewayResources().getStateView().getSelfId()
                .switchIfEmpty(Mono.error(new IllegalStateException("Missing self id")))
                .cache()
                .repeat();

        final GatewayClientGroup clientGroup = voiceChannel.getClient().getGatewayClientGroup();
        final int shardId = (int) ((voiceChannel.getGuildId().asLong() >> 22) % clientGroup.getShardCount());
        final Mono<Void> sendVoiceStateUpdate = clientGroup.unicast(ShardGatewayPayload.voiceStateUpdate(
                VoiceStateUpdate.builder()
                        .guildId(Snowflake.asString(guildId))
                        .channelId(Snowflake.asString(channelId))
                        .selfMute(selfMute)
                        .selfDeaf(selfDeaf)
                        .build(), shardId));

        final Mono<VoiceStateUpdateEvent> waitForVoiceStateUpdate = gateway.getEventDispatcher()
                .on(VoiceStateUpdateEvent.class)
                .zipWith(selfIdSupplier)
                .filter(t2 -> {
                    VoiceStateUpdateEvent vsu = t2.getT1();
                    Long selfId = t2.getT2();
                    final long vsuUser = vsu.getCurrent().getUserId().asLong();
                    final long vsuGuild = vsu.getCurrent().getGuildId().asLong();
                    // this update is for the bot (current) user in this guild
                    return (vsuUser == selfId) && (vsuGuild == guildId);
                })
                .map(Tuple2::getT1)
                .next();

        final Mono<VoiceServerUpdateEvent> waitForVoiceServerUpdate = gateway.getEventDispatcher()
                .on(VoiceServerUpdateEvent.class)
                .filter(vsu -> vsu.getGuildId().asLong() == guildId)
                .filter(vsu -> vsu.getEndpoint() != null) // sometimes Discord sends null here. If so, another VSU
                // should arrive afterwards
                .next();

        final VoiceDisconnectTask disconnectTask = onDisconnectTask(gateway);
        final VoiceServerUpdateTask serverUpdateTask = onServerUpdateTask(gateway);

        Mono<VoiceConnection> newConnection = sendVoiceStateUpdate
                .then(Mono.zip(waitForVoiceStateUpdate, waitForVoiceServerUpdate, selfIdSupplier.next()))
                .flatMap(TupleUtils.function((voiceState, voiceServer, selfId) -> {
                    final String session = voiceState.getCurrent().getSessionId();
                    @SuppressWarnings("ConstantConditions")
                    final VoiceServerOptions voiceServerOptions = new VoiceServerOptions(voiceServer.getToken(),
                            voiceServer.getEndpoint());

                    return gateway.getVoiceConnectionFactory()
                            .create(guildId, selfId, session, voiceServerOptions,
                                    gateway.getCoreResources().getJacksonResources(),
                                    gateway.getGatewayResources().getVoiceReactorResources(),
                                    gateway.getGatewayResources().getVoiceReconnectOptions(),
                                    provider, receiver, sendTaskFactory, receiveTaskFactory, disconnectTask,
                                    serverUpdateTask)
                            .flatMap(vc -> gateway.getVoiceConnectionRegistry().registerVoiceConnection(guildId, vc).thenReturn(vc))
                            .subscriberContext(ctx ->
                                    ctx.put(LogUtil.KEY_GATEWAY_ID, Integer.toHexString(gateway.hashCode()))
                                            .put(LogUtil.KEY_SHARD_ID, shardId)
                                            .put(LogUtil.KEY_GUILD_ID, Snowflake.asString(guildId)));
                }))
                .timeout(timeout)
                .onErrorResume(t -> disconnectTask.onDisconnect(guildId).then(Mono.error(t)));

        return gateway.getVoiceConnectionRegistry().getVoiceConnection(guildId)
                .flatMap(existing -> sendVoiceStateUpdate.then(waitForVoiceStateUpdate).thenReturn(existing))
                .switchIfEmpty(newConnection);
    }

    private static VoiceDisconnectTask onDisconnectTask(GatewayDiscordClient gateway) {
        return guildId -> {
            VoiceStateUpdate voiceStateUpdate = VoiceStateUpdate.builder()
                    .guildId(Snowflake.asString(guildId))
                    .selfMute(false)
                    .selfDeaf(false)
                    .build();
            GatewayClientGroup clientGroup = gateway.getGatewayClientGroup();
            int shardId = (int) ((guildId >> 22) % clientGroup.getShardCount());
            return clientGroup.unicast(ShardGatewayPayload.voiceStateUpdate(voiceStateUpdate, shardId))
                    .then(gateway.getVoiceConnectionRegistry().disconnect(guildId));
        };
    }

    private static VoiceServerUpdateTask onServerUpdateTask(GatewayDiscordClient gateway) {
        return guildId -> {
            //noinspection ConstantConditions
            return gateway.getEventDispatcher()
                    .on(VoiceServerUpdateEvent.class)
                    .filter(vsu -> vsu.getGuildId().asLong() == guildId)
                    .filter(vsu -> vsu.getEndpoint() != null)
                    .map(vsu -> new VoiceServerOptions(vsu.getToken(), vsu.getEndpoint()))
                    .next();
        };
    }
}
