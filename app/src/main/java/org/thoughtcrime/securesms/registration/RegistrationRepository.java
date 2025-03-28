package org.thoughtcrime.securesms.registration;

import android.app.Application;
import android.app.backup.BackupManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.app.NotificationManagerCompat;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.util.KeyHelper;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil;
import org.thoughtcrime.securesms.crypto.PreKeyUtil;
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil;
import org.thoughtcrime.securesms.crypto.SenderKeyUtil;
import org.thoughtcrime.securesms.crypto.storage.PreKeyMetadataStore;
import org.thoughtcrime.securesms.crypto.storage.SignalServiceAccountDataStoreImpl;
import org.thoughtcrime.securesms.database.IdentityTable;
import org.thoughtcrime.securesms.database.RecipientTable;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobs.DirectoryRefreshJob;
import org.thoughtcrime.securesms.jobs.RotateCertificateJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.notifications.NotificationIds;
import org.thoughtcrime.securesms.pin.PinState;
import org.thoughtcrime.securesms.push.AccountManagerFactory;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.service.DirectoryRefreshListener;
import org.thoughtcrime.securesms.service.RotateSignedPreKeyListener;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.KbsPinData;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.account.PreKeyCollection;
import org.whispersystems.signalservice.api.account.PreKeyCollections;
import org.whispersystems.signalservice.api.account.PreKeyUpload;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.PNI;
import org.whispersystems.signalservice.api.push.ServiceIdType;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.ServiceResponse;
import org.whispersystems.signalservice.internal.push.BackupAuthCheckProcessor;
import org.whispersystems.signalservice.internal.push.VerifyAccountResponse;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Operations required for finalizing the registration of an account. This is
 * to be used after verifying the code and registration lock (if necessary) with
 * the server and being issued a UUID.
 */
public final class RegistrationRepository {

  private static final String TAG = Log.tag(RegistrationRepository.class);

  private final Application context;

  public RegistrationRepository(@NonNull Application context) {
    this.context = context;
  }

  public int getRegistrationId() {
    int registrationId = SignalStore.account().getRegistrationId();
    if (registrationId == 0) {
      registrationId = KeyHelper.generateRegistrationId(false);
      SignalStore.account().setRegistrationId(registrationId);
    }
    return registrationId;
  }

  public int getPniRegistrationId() {
    int pniRegistrationId = SignalStore.account().getPniRegistrationId();
    if (pniRegistrationId == 0) {
      pniRegistrationId = KeyHelper.generateRegistrationId(false);
      SignalStore.account().setPniRegistrationId(pniRegistrationId);
    }
    return pniRegistrationId;
  }

  public @NonNull ProfileKey getProfileKey(@NonNull String e164) {
    ProfileKey profileKey = findExistingProfileKey(e164);

    if (profileKey == null) {
      profileKey = ProfileKeyUtil.createNew();
      Log.i(TAG, "No profile key found, created a new one");
    }

    return profileKey;
  }

  public Single<ServiceResponse<VerifyResponse>> registerAccount(@NonNull RegistrationData registrationData,
                                                                 @NonNull VerifyResponse response,
                                                                 boolean setRegistrationLockEnabled)
  {
    return Single.<ServiceResponse<VerifyResponse>>fromCallable(() -> {
      try {
        String pin = response.getPin();
        registerAccountInternal(registrationData, response.getVerifyAccountResponse(), pin, response.getKbsData(), setRegistrationLockEnabled);

        if (pin != null && !pin.isEmpty()) {
          PinState.onPinChangedOrCreated(context, pin, SignalStore.pinValues().getKeyboardType());
        }

        JobManager jobManager = ApplicationDependencies.getJobManager();
        jobManager.add(new DirectoryRefreshJob(false));
        jobManager.add(new RotateCertificateJob());

        DirectoryRefreshListener.schedule(context);
        RotateSignedPreKeyListener.schedule(context);

        return ServiceResponse.forResult(response, 200, null);
      } catch (IOException e) {
        return ServiceResponse.forUnknownError(e);
      }
    }).subscribeOn(Schedulers.io());
  }

  @WorkerThread
  private void registerAccountInternal(@NonNull RegistrationData registrationData,
                                       @NonNull VerifyAccountResponse response,
                                       @Nullable String pin,
                                       @Nullable KbsPinData kbsData,
                                       boolean setRegistrationLockEnabled)
      throws IOException
  {
    ACI     aci    = ACI.parseOrThrow(response.getUuid());
    PNI     pni    = PNI.parseOrThrow(response.getPni());
    boolean hasPin = response.isStorageCapable();

    SignalStore.account().setAci(aci);
    SignalStore.account().setPni(pni);

    ApplicationDependencies.getProtocolStore().aci().sessions().archiveAllSessions();
    ApplicationDependencies.getProtocolStore().pni().sessions().archiveAllSessions();
    SenderKeyUtil.clearAllState();

    SignalServiceAccountDataStoreImpl aciProtocolStore    = ApplicationDependencies.getProtocolStore().aci();
    PreKeyCollection                  aciPreKeyCollection = registrationData.getPreKeyCollections().getAciPreKeyCollection();
    PreKeyMetadataStore               aciMetadataStore    = SignalStore.account().aciPreKeys();

    SignalServiceAccountDataStoreImpl pniProtocolStore    = ApplicationDependencies.getProtocolStore().pni();
    PreKeyCollection                  pniPreKeyCollection = registrationData.getPreKeyCollections().getPniPreKeyCollection();
    PreKeyMetadataStore               pniMetadataStore    = SignalStore.account().pniPreKeys();

    storePreKeys(aciProtocolStore, aciMetadataStore, aciPreKeyCollection);
    storePreKeys(pniProtocolStore, pniMetadataStore, pniPreKeyCollection);


    RecipientTable recipientTable = SignalDatabase.recipients();
    RecipientId    selfId         = Recipient.trustedPush(aci, pni, registrationData.getE164()).getId();

    recipientTable.setProfileSharing(selfId, true);
    recipientTable.markRegisteredOrThrow(selfId, aci);
    recipientTable.linkIdsForSelf(aci, pni, registrationData.getE164());
    recipientTable.setProfileKey(selfId, registrationData.getProfileKey());

    ApplicationDependencies.getRecipientCache().clearSelf();

    SignalStore.account().setE164(registrationData.getE164());
    SignalStore.account().setFcmToken(registrationData.getFcmToken());
    SignalStore.account().setFcmEnabled(registrationData.isFcm());

    long now = System.currentTimeMillis();
    saveOwnIdentityKey(selfId, aciProtocolStore, now);
    saveOwnIdentityKey(selfId, pniProtocolStore, now);

    SignalStore.account().setServicePassword(registrationData.getPassword());
    SignalStore.account().setRegistered(true);
    TextSecurePreferences.setPromptedPushRegistration(context, true);
    TextSecurePreferences.setUnauthorizedReceived(context, false);
    NotificationManagerCompat.from(context).cancel(NotificationIds.UNREGISTERED_NOTIFICATION_ID);

    PinState.onRegistration(context, kbsData, pin, hasPin, setRegistrationLockEnabled);

    ApplicationDependencies.closeConnections();
    ApplicationDependencies.getIncomingMessageObserver();
  }

  public static @Nullable PreKeyCollections generatePreKeys() {
    final IdentityKeyPair     keyPair          = IdentityKeyUtil.generateIdentityKeyPair();
    final PreKeyMetadataStore aciMetadataStore = SignalStore.account().aciPreKeys();
    final PreKeyMetadataStore pniMetadataStore = SignalStore.account().pniPreKeys();

    try {
      return new PreKeyCollections(keyPair,
                                   generatePreKeysForType(ServiceIdType.ACI, keyPair, aciMetadataStore),
                                   generatePreKeysForType(ServiceIdType.PNI, keyPair, pniMetadataStore)
      );
    } catch (IOException e) {
      Log.e(TAG, "Failed to generate prekeys!", e);
      return null;
    }
  }

  private static PreKeyCollection generatePreKeysForType(ServiceIdType serviceIdType, IdentityKeyPair keyPair, PreKeyMetadataStore metadataStore) throws IOException {
    int                nextSignedPreKeyId = metadataStore.getNextSignedPreKeyId();
    SignedPreKeyRecord signedPreKey       = PreKeyUtil.generateSignedPreKey(nextSignedPreKeyId, keyPair.getPrivateKey());
    metadataStore.setActiveSignedPreKeyId(signedPreKey.getId());

    int                ecOneTimePreKeyIdOffset = metadataStore.getNextEcOneTimePreKeyId();
    List<PreKeyRecord> oneTimeEcPreKeys        = PreKeyUtil.generateOneTimeEcPreKeys(ecOneTimePreKeyIdOffset);


    int               nextKyberPreKeyId     = metadataStore.getNextKyberPreKeyId();
    KyberPreKeyRecord lastResortKyberPreKey = PreKeyUtil.generateKyberPreKey(nextKyberPreKeyId, keyPair.getPrivateKey());
    metadataStore.setLastResortKyberPreKeyId(nextKyberPreKeyId);

    int                     oneTimeKyberPreKeyIdOffset = metadataStore.getNextKyberPreKeyId();
    List<KyberPreKeyRecord> oneTimeKyberPreKeys        = PreKeyUtil.generateOneTimeKyberPreKeyRecords(oneTimeKyberPreKeyIdOffset, keyPair.getPrivateKey());

    return new PreKeyCollection(
        nextSignedPreKeyId,
        ecOneTimePreKeyIdOffset,
        nextKyberPreKeyId,
        oneTimeKyberPreKeyIdOffset,
        serviceIdType,
        keyPair.getPublicKey(),
        signedPreKey,
        oneTimeEcPreKeys,
        lastResortKyberPreKey,
        oneTimeKyberPreKeys
    );
  }

  private static void storePreKeys(SignalServiceAccountDataStoreImpl protocolStore, PreKeyMetadataStore metadataStore, PreKeyCollection preKeyCollection) {
    PreKeyUtil.storeSignedPreKey(protocolStore, metadataStore, preKeyCollection.getNextSignedPreKeyId(), preKeyCollection.getSignedPreKey());
    PreKeyUtil.storeOneTimeEcPreKeys(protocolStore, metadataStore, preKeyCollection.getEcOneTimePreKeyIdOffset(), preKeyCollection.getOneTimeEcPreKeys());
    PreKeyUtil.storeLastResortKyberPreKey(protocolStore, metadataStore, preKeyCollection.getLastResortKyberPreKeyId(), preKeyCollection.getLastResortKyberPreKey());
    PreKeyUtil.storeOneTimeKyberPreKeys(protocolStore, metadataStore, preKeyCollection.getOneTimeKyberPreKeyIdOffset(), preKeyCollection.getOneTimeKyberPreKeys());
    metadataStore.setSignedPreKeyRegistered(true);
  }

  private void saveOwnIdentityKey(@NonNull RecipientId selfId, @NonNull SignalServiceAccountDataStoreImpl protocolStore, long now) {
    protocolStore.identities().saveIdentityWithoutSideEffects(selfId,
                                                              protocolStore.getIdentityKeyPair().getPublicKey(),
                                                              IdentityTable.VerifiedStatus.VERIFIED,
                                                              true,
                                                              now,
                                                              true);
  }

  @WorkerThread
  private static @Nullable ProfileKey findExistingProfileKey(@NonNull String e164number) {
    RecipientTable        recipientTable = SignalDatabase.recipients();
    Optional<RecipientId> recipient      = recipientTable.getByE164(e164number);

    if (recipient.isPresent()) {
      return ProfileKeyUtil.profileKeyOrNull(Recipient.resolved(recipient.get()).getProfileKey());
    }

    return null;
  }

  public Single<BackupAuthCheckProcessor> getKbsAuthCredential(@NonNull RegistrationData registrationData, List<String> usernamePasswords) {
    SignalServiceAccountManager accountManager = AccountManagerFactory.getInstance().createUnauthenticated(context, registrationData.getE164(), SignalServiceAddress.DEFAULT_DEVICE_ID, registrationData.getPassword());

    return accountManager.checkBackupAuthCredentials(registrationData.getE164(), usernamePasswords)
                         .map(BackupAuthCheckProcessor::new)
                         .doOnSuccess(processor -> {
                           if (SignalStore.kbsValues().removeAuthTokens(processor.getInvalid())) {
                             new BackupManager(context).dataChanged();
                           }
                         });
  }

}
