package com.lu.uni.igorzfeel.passport_reader

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.LDSFileUtil
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG1File
import java.io.InputStream

class LoggingActivity : AppCompatActivity() {

    companion object {
        val TAG: String  = "LoggingActivity"
    }

    // dates has to be of the "yymmdd" format
    private var passportNumber: String = "AA000000"
    private var expirationDate: String = "210101"
    private var birthDate: String = "000101"
    private var can: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logging)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val passportBundle: Bundle = getIntent().getBundleExtra("passportBundle")
        extractBundle(passportBundle)

        var nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        Log.d(TAG,"NFC supported ${(nfcAdapter != null).toString()}")
        Log.d(TAG, "NFC enabled ${(nfcAdapter?.isEnabled).toString()}")
    }

    private fun extractBundle(bundle: Bundle) {
        can = bundle.getString("can")
        Log.d(TAG,"Bundle has been extracted")
        Log.d(TAG,"CAN " + can)
    }

    override fun onResume() {
        super.onResume()

        val adapter = NfcAdapter.getDefaultAdapter(this)

        if (adapter != null) {
            if (!adapter.isEnabled()) {
                Toast.makeText(this, "NFC is off, turn it on!", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(applicationContext, this.javaClass)
            intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            val pendingIntent =
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
            val filter = arrayOf(arrayOf("android.nfc.tech.IsoDep"))
            adapter.enableForegroundDispatch(this, pendingIntent, null, filter)
        }
        else {
            Toast.makeText(this, "Device doesn't support NFC", Toast.LENGTH_SHORT).show()
            return
        }
    }

    override fun onPause() {
        super.onPause()

        val adapter = NfcAdapter.getDefaultAdapter(this)
        adapter?.disableForegroundDispatch(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if(intent == null)
            return
        if(NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.action)) {
            Log.d(TAG, "NFC card has been discovered")
            var tag: Tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            if (tag.techList.toList().contains("android.nfc.tech.IsoDep")) {
                Log.d(TAG, "This is Iso supported tag")
                readPassport(IsoDep.get(tag))
            }
            else {
                Log.d(TAG, "I don't know this card")
            }
        }
    }

    private fun readPassport(isoDep: IsoDep) {
        Log.d(TAG, "Let's read that passport")

        if (passportNumber.isNotEmpty()
            && expirationDate.isNotEmpty()
            && birthDate.isNotEmpty()
            && can!!.isNotEmpty()) {
            Log.d(TAG, "Fields aren't empty")
        }
        else {
            Log.d(TAG, "[ERROR] Fields are empty")
            Toast.makeText(this, "Empty field is not allowed", Toast.LENGTH_SHORT).show()
            return
        }

        val bacKey = BACKey(passportNumber, birthDate, expirationDate)
        val paceKey = PACEKeySpec.createCANKey(can)

        try {
            val cardService = CardService.getInstance(isoDep)
            cardService.open()

            val passportService = PassportService(cardService
                , PassportService.NORMAL_MAX_TRANCEIVE_LENGTH
                , PassportService.DEFAULT_MAX_BLOCKSIZE
                , false
                , true)
            passportService.open()

            var paceSucceeded = false
            try {
                val cardAccessFile = CardAccessFile(passportService.getInputStream(PassportService.EF_CARD_ACCESS))
                val secInfos = cardAccessFile.securityInfos
                Log.d(TAG, cardAccessFile.toString())

                if (secInfos != null && secInfos.isNotEmpty()) {
                    Log.d(TAG, "PACE info has been found")
                    val paceInfo = secInfos.iterator().next() as PACEInfo
                    val oid = paceInfo.objectIdentifier
                    val paramId = paceInfo.parameterId
                    val params = PACEInfo.toParameterSpec(paramId)

                    Log.d(TAG, paceInfo.protocolOIDString)
                    Log.d(TAG, PACEInfo.toStandardizedParamIdString(paramId))

                    passportService.doPACE(paceKey, oid, params, paramId)
                    paceSucceeded = true
                    Log.d(TAG, "PACE has succeeded")
                }
            } catch (e: Exception) {
                Log.d(TAG, "PACE has failed with next error:")
                Log.d(TAG, e.toString())
            }

            passportService.sendSelectApplet(paceSucceeded)

            if (!paceSucceeded) {
                Log.d(TAG, "Let's try out to proceed with BAC")
                passportService.doBAC(bacKey)
                Log.d(TAG, "BAC success")
            }

            var inputStream: InputStream = passportService.getInputStream(PassportService.EF_DG1)
            val dg1 = LDSFileUtil.getLDSFile(PassportService.EF_DG1, inputStream) as DG1File
            Log.d(TAG, dg1.mrzInfo.nationality)
            Log.d(TAG, dg1.mrzInfo.documentNumber)
            Log.d(TAG, dg1.mrzInfo.dateOfExpiry)
            Log.d(TAG, dg1.mrzInfo.gender.toString())

        } catch (e: Exception) {
            Log.d(TAG, e.toString())
            Toast.makeText(this, e.toString(), Toast.LENGTH_SHORT).show()
        }
    }
}