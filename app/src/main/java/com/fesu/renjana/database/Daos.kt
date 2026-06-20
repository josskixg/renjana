package com.fesu.renjana.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for instances
 */
@Dao
interface InstanceDao {
    @Query("SELECT * FROM instances ORDER BY lastUsed DESC")
    fun getAllInstances(): Flow<List<InstanceEntity>>

    @Query("SELECT * FROM instances WHERE id = :id")
    suspend fun getInstanceById(id: String): InstanceEntity?

    @Query("SELECT * FROM instances WHERE packageName = :packageName")
    suspend fun getInstancesByPackage(packageName: String): List<InstanceEntity>

    @Query("SELECT * FROM instances WHERE accountId = :accountId")
    suspend fun getInstancesByAccount(accountId: String): List<InstanceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstance(instance: InstanceEntity)

    @Update
    suspend fun updateInstance(instance: InstanceEntity)

    @Delete
    suspend fun deleteInstance(instance: InstanceEntity)

    @Query("DELETE FROM instances WHERE id = :id")
    suspend fun deleteInstanceById(id: String)

    @Query("UPDATE instances SET lastUsed = :timestamp, isActive = :isActive WHERE id = :id")
    suspend fun updateInstanceUsage(id: String, timestamp: Long, isActive: Boolean)

    @Query("UPDATE instances SET appName = :appName WHERE id = :id")
    suspend fun updateAppName(id: String, appName: String)

    @Query("UPDATE instances SET enableGms = :enableGms, enableFingerprint = :enableFingerprint, spoofSignature = :spoofSignature, enableAntiDetection = :enableAntiDetection WHERE id = :id")
    suspend fun updateInstanceConfig(id: String, enableGms: Boolean, enableFingerprint: Boolean, spoofSignature: Boolean, enableAntiDetection: Boolean)

    @Query("UPDATE instances SET spoofModel = :model, spoofBrand = :brand, spoofManufacturer = :manufacturer, spoofAndroidVersion = :androidVersion, spoofAndroidId = :androidId, spoofSerial = :serial WHERE id = :id")
    suspend fun updateDeviceSpoof(id: String, model: String?, brand: String?, manufacturer: String?, androidVersion: String?, androidId: String?, serial: String?)

    @Query("UPDATE instances SET canvasHash = :canvasHash, canvasNoise = :canvasNoise, screenDensityDpi = :screenDensityDpi, screenWidthDp = :screenWidthDp, screenHeightDp = :screenHeightDp, screenRefreshRate = :screenRefreshRate, sensorAccelerometer = :sensorAccelerometer, sensorGyroscope = :sensorGyroscope, sensorMagnetometer = :sensorMagnetometer, sensorBarometer = :sensorBarometer, sensorProximity = :sensorProximity, batteryCapacityMah = :batteryCapacityMah, wifiMacPrefix = :wifiMacPrefix WHERE id = :id")
    suspend fun updateExtendedFingerprint(id: String, canvasHash: String?, canvasNoise: Float?, screenDensityDpi: Int?, screenWidthDp: Int?, screenHeightDp: Int?, screenRefreshRate: Float?, sensorAccelerometer: Boolean?, sensorGyroscope: Boolean?, sensorMagnetometer: Boolean?, sensorBarometer: Boolean?, sensorProximity: Boolean?, batteryCapacityMah: Int?, wifiMacPrefix: String?)

    @Query("SELECT COUNT(*) FROM instances")
    fun getInstanceCount(): Flow<Int>
}

/**
 * Data Access Object for Google accounts
 */
@Dao
interface GoogleAccountDao {
    @Query("SELECT * FROM google_accounts ORDER BY createdAt DESC")
    fun getAllAccounts(): Flow<List<GoogleAccountEntity>>

    @Query("SELECT * FROM google_accounts WHERE id = :id")
    suspend fun getAccountById(id: String): GoogleAccountEntity?

    @Query("SELECT * FROM google_accounts WHERE email = :email")
    suspend fun getAccountByEmail(email: String): GoogleAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: GoogleAccountEntity)

    @Update
    suspend fun updateAccount(account: GoogleAccountEntity)

    @Delete
    suspend fun deleteAccount(account: GoogleAccountEntity)

    @Query("DELETE FROM google_accounts WHERE id = :id")
    suspend fun deleteAccountById(id: String)

    @Query("UPDATE google_accounts SET idToken = :idToken, accessToken = :accessToken, tokenExpiryTime = :expiryTime WHERE id = :id")
    suspend fun updateAccountTokens(id: String, idToken: String, accessToken: String?, expiryTime: Long)
}
