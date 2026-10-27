package de.fiereu.openmmo.server.game.handler

import de.fiereu.network.Side
import de.fiereu.network.coroutines.CoroutineProtocolHandler
import de.fiereu.openmmo.common.enums.ChatType
import de.fiereu.openmmo.common.enums.Language
import de.fiereu.openmmo.net.game.GameProtocol
import de.fiereu.openmmo.net.game.packets.AddFriendPacket
import de.fiereu.openmmo.net.game.packets.BlockPlayerPacket
import de.fiereu.openmmo.net.game.packets.CancelSocialInteractionPacket
import de.fiereu.openmmo.net.game.packets.ChatMessagePacket
import de.fiereu.openmmo.net.game.packets.ChatMessageSendPacket
import de.fiereu.openmmo.net.game.packets.CreateCharacterPacket
import de.fiereu.openmmo.net.game.packets.CreateMarketListingPacket
import de.fiereu.openmmo.net.game.packets.DeleteCharacterPacket
import de.fiereu.openmmo.net.game.packets.DialogChoicePacket
import de.fiereu.openmmo.net.game.packets.DuelChallengePacket
import de.fiereu.openmmo.net.game.packets.EntityInteractPacket
import de.fiereu.openmmo.net.game.packets.ExchangeItemRequestPacket
import de.fiereu.openmmo.net.game.packets.FaceDirectionPacket
import de.fiereu.openmmo.net.game.packets.FieldItemUsePacket
import de.fiereu.openmmo.net.game.packets.InGameChallengeResponsePacket
import de.fiereu.openmmo.net.game.packets.JoinPacket
import de.fiereu.openmmo.net.game.packets.KeepAlivePacket
import de.fiereu.openmmo.net.game.packets.MapLoadedAckPacket
import de.fiereu.openmmo.net.game.packets.MovementPacket
import de.fiereu.openmmo.net.game.packets.NullPacket
import de.fiereu.openmmo.net.game.packets.RemoveFriendPacket
import de.fiereu.openmmo.net.game.packets.RequestCharactersPacket
import de.fiereu.openmmo.net.game.packets.RequestPlayerPacket
import de.fiereu.openmmo.net.game.packets.RequestSocialProfilePacket
import de.fiereu.openmmo.net.game.packets.SelectCharacterPacket
import de.fiereu.openmmo.net.game.packets.ShopSellRequestPacket
import de.fiereu.openmmo.net.game.packets.SwapInventorySlotsPacket
import de.fiereu.openmmo.net.game.packets.TileInteractPacket
import de.fiereu.openmmo.net.game.packets.TradeActionPacket
import de.fiereu.openmmo.net.game.packets.TradeSelectMonPacket
import de.fiereu.openmmo.net.game.packets.UnblockPlayerPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleActionPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleActionSelectPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleActionSubmitPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleAppearancePacket
import de.fiereu.openmmo.net.game.packets.battle.BattleCancelRequestPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleChatMessagePacket
import de.fiereu.openmmo.net.game.packets.battle.BattleLeavePacket
import de.fiereu.openmmo.net.game.packets.battle.BattlePartySlotSelectPacket
import de.fiereu.openmmo.net.game.packets.battle.BattlePartySwitchPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleReadyPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleRewardSelectPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSequencePacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSimulationRequestPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSlotActionPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSwitchSelectionsPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleTargetPickPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleTeamPreviewConfirmPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleTierSelectPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleTransitionReadyPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleUseItemPacket
import de.fiereu.openmmo.net.game.packets.battle.DuelRulesChallengePacket
import de.fiereu.openmmo.net.game.packets.battle.moves.MoveLearnReplyPacket
import de.fiereu.openmmo.net.game.packets.dialog.DialogActionResponsePacket
import de.fiereu.openmmo.net.game.packets.gtl.GtlConfirmPurchasePacket
import de.fiereu.openmmo.net.game.packets.gtl.GtlListingCancelPacket
import de.fiereu.openmmo.net.game.packets.gtl.GtlSearchPageRequestPacket
import de.fiereu.openmmo.net.game.packets.guild.GuildActivityLogPageRequestPacket
import de.fiereu.openmmo.net.game.packets.guild.GuildCreatePacket
import de.fiereu.openmmo.net.game.packets.guild.GuildDisbandPacket
import de.fiereu.openmmo.net.game.packets.guild.GuildInvitePacket
import de.fiereu.openmmo.net.game.packets.guild.GuildLeavePacket
import de.fiereu.openmmo.net.game.packets.guild.GuildMemberKickPacket
import de.fiereu.openmmo.net.game.packets.guild.GuildMemberRankAssignPacket
import de.fiereu.openmmo.net.game.packets.guild.GuildMotdUpdatePacket
import de.fiereu.openmmo.net.game.packets.guild.GuildRankLabelUpdatePacket
import de.fiereu.openmmo.net.game.packets.guild.GuildRankPermissionUpdatePacket
import de.fiereu.openmmo.net.game.packets.market.MarketInventoryRequestPacket
import de.fiereu.openmmo.net.game.packets.progress.ProgressActionPacket
import de.fiereu.openmmo.net.game.packets.social.MarketQueryPacket
import de.fiereu.openmmo.net.game.packets.social.SocialRequestPacket
import de.fiereu.openmmo.net.game.packets.social.TradeOfferPcMonPacket
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferItemPacket
import de.fiereu.openmmo.net.game.packets.trade.TradeRequestPacket
import de.fiereu.openmmo.server.game.progression.ProgressActionService
import de.fiereu.openmmo.server.game.script.ScriptRunner
import de.fiereu.openmmo.server.game.services.BattleService
import de.fiereu.openmmo.server.game.services.DialogService
import de.fiereu.openmmo.server.game.services.FieldItemService
import de.fiereu.openmmo.server.game.services.GuildService
import de.fiereu.openmmo.server.game.services.InteractionService
import de.fiereu.openmmo.server.game.services.LoginService
import de.fiereu.openmmo.server.game.services.MarketService
import de.fiereu.openmmo.server.game.services.MovementService
import de.fiereu.openmmo.server.game.services.MultiplayerService
import de.fiereu.openmmo.server.game.services.PartyStorageService
import de.fiereu.openmmo.server.game.services.PresenceService
import de.fiereu.openmmo.server.game.services.PvpBattleService
import de.fiereu.openmmo.server.game.services.ShopService
import de.fiereu.openmmo.server.game.services.SocialService
import de.fiereu.openmmo.server.game.services.command.ChatCommandService
import de.fiereu.openmmo.server.game.services.social.MarketClerks
import de.fiereu.openmmo.server.game.services.trade.TradeService
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.session.SCRIPT_SCOPE
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

private val log = KotlinLogging.logger {}

class GameAppHandler
@Inject
constructor(
    private val loginService: LoginService,
    private val movementService: MovementService,
    private val dialogService: DialogService,
    private val interactionService: InteractionService,
    private val multiplayerService: MultiplayerService,
    private val presenceService: PresenceService,
    private val socialService: SocialService,
    private val guildService: GuildService,
    private val battleService: BattleService,
    private val pvpBattleService: PvpBattleService,
    private val evolutionService: de.fiereu.openmmo.server.game.services.EvolutionService,
    private val chatCommandService: ChatCommandService,
    private val shopService: ShopService,
    private val fieldItemService: FieldItemService,
    private val partyStorageService: PartyStorageService,
    private val tradeService: TradeService,
    private val marketService: MarketService,
    private val progressActions: ProgressActionService,
    private val scriptRunner: ScriptRunner,
    private val sessionRegistry: SessionRegistry,
    private val characterStore: CharacterStore,
    private val fieldService: de.fiereu.openmmo.server.game.services.FieldService,
    private val secretBaseService: de.fiereu.openmmo.server.game.services.SecretBaseService,
    private val marketClerks: MarketClerks,
    private val contestActions: de.fiereu.openmmo.server.game.contest.ContestActionService,
    scope: CoroutineScope,
) : CoroutineProtocolHandler<GameProtocol>(GameProtocol, Side.SERVER, scope) {

  init {
    onSuspend<JoinPacket> { event -> loginService.onJoinGame(event) }
    onSuspend<CreateCharacterPacket> { event -> loginService.onCreateCharacter(event) }
    onSuspend<RequestCharactersPacket> { event -> loginService.onCharacterRequest(event) }
    onSuspend<SelectCharacterPacket> { event -> loginService.onCharacterSelected(event) }
    onSuspend<DeleteCharacterPacket> { event -> loginService.onDeleteCharacter(event) }
    onSuspend<RequestPlayerPacket> { event -> loginService.onRequestPlayer(event) }

    // r3-social: no walking while a trade window is open.
    onSuspend<MovementPacket> { event ->
      if (!tradeService.refuseStep(event.session)) movementService.onMovement(event)
    }
    onSuspend<FaceDirectionPacket> { event -> movementService.onFaceDirection(event) }

    onSuspend<EntityInteractPacket> { event ->
      when {
        tradeService.refuseFieldAction(event.session) -> Unit
        marketClerks.isClerk(event.packet.entityId) -> marketService.openFor(event.session)
        else -> interactionService.onEntityInteract(event)
      }
    }
    onSuspend<TileInteractPacket> { event ->
      if (!tradeService.refuseFieldAction(event.session)) interactionService.onTileInteract(event)
    }
    onSuspend<de.fiereu.openmmo.net.game.packets.field.FieldActionPacket> { event ->
      if (!tradeService.refuseFieldAction(event.session)) fieldService.onAction(event)
    }
    // r4: the secret base PC.
    onSuspend<de.fiereu.openmmo.net.game.packets.field.SecretBaseActionPacket> { event ->
      secretBaseService.onAction(event)
    }
    // r5-contests: contests, blender, pokeblocks, berry trees.
    onSuspend<de.fiereu.openmmo.net.game.packets.contest.ContestActionPacket> { event ->
      contestActions.onAction(event)
    }
    onSuspend<DialogActionResponsePacket> { event -> dialogService.onInteractive(event) }
    onSuspend<DialogChoicePacket> { event -> dialogService.onDialogChoice(event) }
    onSuspend<ExchangeItemRequestPacket> { event ->
      if (!tradeService.refuseFieldAction(event.session)) shopService.onBuy(event)
    }
    onSuspend<ShopSellRequestPacket> { event ->
      if (!tradeService.refuseFieldAction(event.session)) shopService.onSell(event)
    }
    onSuspend<FieldItemUsePacket> { event -> fieldItemService.onUse(event) }
    onSuspend<ProgressActionPacket> { event -> progressActions.onAction(event) }
    onSuspend<SwapInventorySlotsPacket> { event -> partyStorageService.onSwap(event) }

    onSuspend<CreateMarketListingPacket> { event -> marketService.onCreate(event) }
    onSuspend<GtlSearchPageRequestPacket> { event -> marketService.onSearch(event) }
    onSuspend<GtlConfirmPurchasePacket> { event -> marketService.onBuy(event) }
    onSuspend<GtlListingCancelPacket> { event -> marketService.onCancel(event) }
    on<MarketInventoryRequestPacket> { event -> marketService.onInventory(event) }
    onSuspend<MarketQueryPacket> { event -> marketService.onQuery(event) }
    onSuspend<SocialRequestPacket> { event -> socialService.onRequest(event) }

    onSuspend<AddFriendPacket> { event -> socialService.onAddFriend(event) }
    onSuspend<RemoveFriendPacket> { event -> socialService.onRemoveFriend(event) }
    onSuspend<BlockPlayerPacket> { event -> socialService.onBlockPlayer(event) }
    onSuspend<UnblockPlayerPacket> { event -> socialService.onUnblockPlayer(event) }
    on<RequestSocialProfilePacket> { event -> socialService.onRequestSocialProfile(event) }
    on<CancelSocialInteractionPacket> { event -> socialService.onCancelSocialInteraction(event) }

    onSuspend<GuildCreatePacket> { event -> guildService.onCreateGuild(event) }
    onSuspend<GuildInvitePacket> { event -> guildService.onGuildInvite(event) }
    onSuspend<GuildRankPermissionUpdatePacket> { event ->
      guildService.onRankPermissionUpdate(event)
    }
    onSuspend<GuildMemberRankAssignPacket> { event -> guildService.onRankAssign(event) }
    onSuspend<GuildMemberKickPacket> { event -> guildService.onKick(event) }
    onSuspend<GuildLeavePacket> { event -> guildService.onLeave(event) }
    onSuspend<GuildDisbandPacket> { event -> guildService.onDisband(event) }
    onSuspend<GuildMotdUpdatePacket> { event -> guildService.onMotdUpdate(event) }
    onSuspend<GuildRankLabelUpdatePacket> { event -> guildService.onRankLabelUpdate(event) }
    onSuspend<GuildActivityLogPageRequestPacket> { event ->
      guildService.onActivityLogPageRequest(event)
    }

    onSuspend<MoveLearnReplyPacket> { event -> battleService.onMoveLearnReply(event) }
    on<BattlePartySwitchPacket> { event -> battleService.onBattlePacket(event) }
    on<BattleActionPacket> { event -> battleService.onBattlePacket(event) }
    onSuspend<BattleActionSelectPacket> { event -> battleService.onBattleAction(event) }
    // r3-battle: positional/targeted actions and the evolution scene answer.
    onSuspend<de.fiereu.openmmo.net.game.packets.battle.BattleExtActionPacket> { event ->
      battleService.onExtAction(event)
    }
    onSuspend<de.fiereu.openmmo.net.game.packets.battle.EvolutionReplyPacket> { event ->
      evolutionService.onReply(event)
    }
    on<BattleLeavePacket> { event -> battleService.onBattlePacket(event) }
    on<BattleSequencePacket> { event -> battleService.onBattlePacket(event) }
    on<BattleSlotActionPacket> { event -> battleService.onBattlePacket(event) }
    on<BattleSwitchSelectionsPacket> { event -> battleService.onBattlePacket(event) }
    on<BattleUseItemPacket> { event -> battleService.onBattlePacket(event) }
    on<BattleAppearancePacket> { event -> battleService.onBattlePacket(event) }
    on<BattleCancelRequestPacket> { event -> battleService.onBattlePacket(event) }
    on<BattleSimulationRequestPacket> { event -> battleService.onBattlePacket(event) }
    on<BattleReadyPacket> { event -> battleService.onBattlePacket(event) }
    on<BattleActionSubmitPacket> { event -> battleService.onBattlePacket(event) }
    on<BattleTierSelectPacket> { event -> battleService.onBattlePacket(event) }
    on<BattleChatMessagePacket> { event -> battleService.onBattlePacket(event) }
    on<BattlePartySlotSelectPacket> { event -> battleService.onBattlePacket(event) }
    on<BattleTargetPickPacket> { event -> battleService.onBattlePacket(event) }
    on<BattleTransitionReadyPacket> { event -> battleService.onBattlePacket(event) }
    on<BattleTeamPreviewConfirmPacket> { event -> battleService.onBattlePacket(event) }
    on<BattleRewardSelectPacket> { event -> battleService.onBattlePacket(event) }
    onSuspend<MapLoadedAckPacket> { event -> battleService.onClientReady(event) }
    onSuspend<DuelChallengePacket> { event -> pvpBattleService.onDuelChallenge(event) }
    onSuspend<DuelRulesChallengePacket> { event -> pvpBattleService.onRulesChallenge(event) }
    onSuspend<InGameChallengeResponsePacket> { event ->
      pvpBattleService.onChallengeResponse(event)
    }

    onSuspend<TradeRequestPacket> { event -> tradeService.onRequest(event) }
    on<TradeOfferPcMonPacket> { event -> tradeService.onSelectPcMon(event) }
    onSuspend<TradeActionPacket> { event -> tradeService.onAction(event) }
    on<TradeSelectMonPacket> { event -> tradeService.onSelectMon(event) }
    on<TradeOfferItemPacket> { event -> tradeService.onOfferItem(event) }

    // The client sends an empty heartbeat packet.
    on<NullPacket> {}
    on<KeepAlivePacket> { event -> event.session.send(event.packet) }
    onSuspend<ChatMessagePacket> { event -> socialService.onLegacyChat(event) }
    // What the client sends when the player types. The text rides in target unless the mode
    // carries a message of its own.
    onSuspend<ChatMessageSendPacket> { event ->
      chatCommandService.tryHandle(event.session, event.packet.message ?: event.packet.target)
    }
  }

  override fun onInactive() {
    // A cancelled scope left behind would make every later launch a silent no-op.
    session.attributes.remove(SCRIPT_SCOPE)?.cancel()
    val state = session.attributes[PLAYER_STATE] ?: return
    log.info { "Player ${state.characterId} disconnected." }
    val charId = state.characterId
    val name = charId?.let { characterStore.getCharacter(it)?.info?.name }
    if (charId != null) {
      // The battle flush must land before the unload evicts the character from the cache, and
      // before the rollback, which would otherwise be overwritten by the party it persists.
      battleService.onDisconnect(session)
      tradeService.onDisconnect(charId)
      progressActions.onLeave(charId)
      contestActions.onDisconnect(session, charId)
      socialService.onDisconnect(charId, state.userId)
      // Undo the interrupted script here rather than leaving it to the coroutine's own cleanup,
      // which runs on another thread and would race the flush below.
      scriptRunner.rollBack(session, state, entityId = -1)
      state.inDialog = false
      presenceService.leave(session)
      sessionRegistry.unbindCharacter(charId)
      characterStore.unloadCharacterAsync(charId)
    }
    multiplayerService.broadcastMessage(
        ChatMessagePacket(
            type = ChatType.GAME_NOTIFICATIONS,
            language = Language.EN,
            message =
                if (name != null) "Player $name left the game." else "A player left the game.",
            sender = "",
        ),
    )
  }
}
