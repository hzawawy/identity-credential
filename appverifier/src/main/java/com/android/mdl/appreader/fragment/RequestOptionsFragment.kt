package com.android.mdl.appreader.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.android.mdl.appreader.databinding.FragmentRequestOptionsBinding
import com.android.mdl.appreader.document.RequestMdl
import com.android.mdl.appreader.document.RequestMvr
import com.android.mdl.appreader.transfer.TransferManager

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class RequestOptionsFragment : Fragment() {

    private val args: RequestOptionsFragmentArgs by navArgs()
    private var _binding: FragmentRequestOptionsBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    // TODO: get intent to retain from user
    private var mapSelectedDataItems = getSelectRequestMdlMandatory(false)
    private var keepConnection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepConnection = args.keepConnection
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRequestOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!keepConnection) {
            // Always call to cancel any connection that could be on progress
            TransferManager.getInstance(requireContext()).stopVerification()
        }

        binding.cbRequestMdl.setOnClickListener {
            // Now we only have mDL option, it is always true
            binding.cbRequestMdl.isChecked = true
            binding.cbRequestMvr.isChecked = false
            binding.cbRequestMdlOlder18.isEnabled = true
            binding.cbRequestMdlOlder21.isEnabled = true
            binding.cbRequestMdlMandatory.isEnabled = true
            binding.cbRequestMdlFull.isEnabled = true
            binding.cbRequestMdlCustom.isEnabled = true
        }

        binding.cbRequestMdlOlder18.setOnClickListener {
            binding.cbRequestMdlOlder18.isChecked = true
            binding.cbRequestMdlOlder21.isChecked = false
            binding.cbRequestMdlMandatory.isChecked = false
            binding.cbRequestMdlFull.isChecked = false
            binding.cbRequestMdlCustom.isChecked = false
            // TODO: get intent to retain from user
            mapSelectedDataItems = getSelectRequestMdlOlder18(false)
        }
        binding.cbRequestMdlOlder21.setOnClickListener {
            binding.cbRequestMdlOlder18.isChecked = false
            binding.cbRequestMdlOlder21.isChecked = true
            binding.cbRequestMdlMandatory.isChecked = false
            binding.cbRequestMdlFull.isChecked = false
            binding.cbRequestMdlCustom.isChecked = false
            // TODO: get intent to retain from user
            mapSelectedDataItems = getSelectRequestMdlOlder21(false)
        }
        binding.cbRequestMdlMandatory.setOnClickListener {
            binding.cbRequestMdlOlder18.isChecked = false
            binding.cbRequestMdlOlder21.isChecked = false
            binding.cbRequestMdlMandatory.isChecked = true
            binding.cbRequestMdlFull.isChecked = false
            binding.cbRequestMdlCustom.isChecked = false
            // TODO: get intent to retain from user
            mapSelectedDataItems = getSelectRequestMdlMandatory(false)
        }
        binding.cbRequestMdlFull.setOnClickListener {
            binding.cbRequestMdlOlder18.isChecked = false
            binding.cbRequestMdlOlder21.isChecked = false
            binding.cbRequestMdlMandatory.isChecked = false
            binding.cbRequestMdlFull.isChecked = true
            binding.cbRequestMdlCustom.isChecked = false
            // TODO: get intent to retain from user
            mapSelectedDataItems = getSelectRequestMdlFull(false)

        }
        binding.cbRequestMdlCustom.setOnClickListener {
            binding.cbRequestMdlOlder18.isChecked = false
            binding.cbRequestMdlOlder21.isChecked = false
            binding.cbRequestMdlMandatory.isChecked = false
            binding.cbRequestMdlFull.isChecked = false
            binding.cbRequestMdlCustom.isChecked = true
        }

        binding.cbRequestMvr.setOnClickListener {
            // Now we only have mDL option, it is always true
            binding.cbRequestMdl.isChecked = false
            binding.cbRequestMvr.isChecked = true
            binding.cbRequestMdlOlder18.isEnabled = false
            binding.cbRequestMdlOlder21.isEnabled = false
            binding.cbRequestMdlMandatory.isEnabled = false
            binding.cbRequestMdlFull.isEnabled = false
            binding.cbRequestMdlCustom.isEnabled = false
            // TODO: get intent to retain from user
            mapSelectedDataItems = getSelectRequestMvrFull(false)
        }

        binding.btNext.setOnClickListener {
            if (binding.cbRequestMdl.isChecked && binding.cbRequestMdlCustom.isChecked) {
                findNavController().navigate(
                    RequestOptionsFragmentDirections.actionRequestOptionsToRequestCustom(
                        RequestMdl,
                        keepConnection
                    )
                )
            } else {
                val requestDocument = if (binding.cbRequestMdl.isChecked) {
                    RequestMdl
                } else {
                    RequestMvr
                }
                requestDocument.setSelectedDataItems(mapSelectedDataItems)
                //Navigate direct to transfer screnn
                if (keepConnection) {
                    findNavController().navigate(
                        RequestOptionsFragmentDirections.actionRequestOptionsToTransfer(
                            requestDocument,
                            true
                        )
                    )
                } else {
                    findNavController().navigate(
                        RequestOptionsFragmentDirections.actionRequestOptionsToScanDeviceEngagement(
                            requestDocument
                        )
                    )
                }
            }
        }
    }

    private fun getSelectRequestMdlFull(intentToRetain: Boolean): Map<String, Boolean> {
        val map = mutableMapOf<String, Boolean>()
        RequestMdl.dataItems.forEach {
            map[it.identifier] = intentToRetain
        }
        return map
    }

    private fun getSelectRequestMdlMandatory(intentToRetain: Boolean): Map<String, Boolean> {
        val map = mutableMapOf<String, Boolean>()
        map[RequestMdl.DataItems.FAMILY_NAME.identifier] = intentToRetain
        map[RequestMdl.DataItems.GIVEN_NAMES.identifier] = intentToRetain
        map[RequestMdl.DataItems.BIRTH_DATE.identifier] = intentToRetain
        map[RequestMdl.DataItems.ISSUE_DATE.identifier] = intentToRetain
        map[RequestMdl.DataItems.EXPIRY_DATE.identifier] = intentToRetain
        map[RequestMdl.DataItems.ISSUING_COUNTRY.identifier] = intentToRetain
        map[RequestMdl.DataItems.ISSUING_AUTHORITY.identifier] = intentToRetain
        map[RequestMdl.DataItems.DOCUMENT_NUMBER.identifier] = intentToRetain
        map[RequestMdl.DataItems.PORTRAIT.identifier] = intentToRetain
        map[RequestMdl.DataItems.DRIVING_PRIVILEGES.identifier] = intentToRetain
        map[RequestMdl.DataItems.UN_DISTINGUISHING_SIGN.identifier] = intentToRetain
        return map
    }

    private fun getSelectRequestMdlOlder21(intentToRetain: Boolean): Map<String, Boolean> {
        val map = mutableMapOf<String, Boolean>()
        map[RequestMdl.DataItems.PORTRAIT.identifier] = intentToRetain
        map[RequestMdl.DataItems.AGE_OVER_21.identifier] = intentToRetain
        return map
    }

    private fun getSelectRequestMdlOlder18(intentToRetain: Boolean): Map<String, Boolean> {
        val map = mutableMapOf<String, Boolean>()
        map[RequestMdl.DataItems.PORTRAIT.identifier] = intentToRetain
        map[RequestMdl.DataItems.AGE_OVER_18.identifier] = intentToRetain
        return map
    }

    private fun getSelectRequestMvrFull(intentToRetain: Boolean): Map<String, Boolean> {
        val map = mutableMapOf<String, Boolean>()
        RequestMvr.dataItems.forEach {
            map[it.identifier] = intentToRetain
        }
        return map
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}