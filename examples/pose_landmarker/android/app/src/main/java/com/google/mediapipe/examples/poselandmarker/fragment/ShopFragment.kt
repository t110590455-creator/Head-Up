package com.google.mediapipe.examples.poselandmarker.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.mediapipe.examples.poselandmarker.HeadUpRepository
import com.google.mediapipe.examples.poselandmarker.HeadUpUiState
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.ShopItem
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentShopBinding
import com.google.mediapipe.examples.poselandmarker.databinding.ItemShopItemBinding

class ShopFragment : Fragment() {
    private var _binding: FragmentShopBinding? = null
    private val binding get() = _binding!!
    private var latestState = HeadUpUiState()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentShopBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        render(HeadUpRepository.currentState(requireContext()))
        HeadUpRepository.observeState().observe(viewLifecycleOwner) { render(it) }
    }

    private fun render(state: HeadUpUiState) {
        latestState = state
        binding.shopCoinValue.text = "%,d".format(state.coins)
        val rows = listOf(binding.shopItemOne, binding.shopItemTwo, binding.shopItemThree, binding.shopItemFour)
        val labels = listOf(
            Triple(R.string.shop_starlight_armor, R.string.shop_starlight_detail, "A"),
            Triple(R.string.shop_ocean_background, R.string.shop_ocean_detail, "B"),
            Triple(R.string.shop_eye_time_ticket, R.string.shop_eye_time_detail, "T"),
            Triple(R.string.shop_focus_badge, R.string.shop_focus_detail, "F"),
        )
        state.shopItems.zip(rows).forEachIndexed { index, (item, row) ->
            val label = labels[index]
            bindItem(row, item, getString(label.first), getString(label.second), label.third, state.coins)
        }
    }

    private fun bindItem(
        row: ItemShopItemBinding,
        item: ShopItem,
        title: String,
        detail: String,
        icon: String,
        coins: Int,
    ) {
        row.shopItemIcon.text = icon
        row.shopItemTitle.text = title
        row.shopItemDetail.text = detail
        row.shopItemCost.text = when {
            item.isOwned -> getString(R.string.shop_owned)
            else -> getString(R.string.shop_cost_format, item.cost)
        }
        row.shopItemCost.isEnabled = !item.isOwned
        row.root.alpha = if (item.isOwned) 0.7f else 1f
        row.shopItemCost.setOnClickListener {
            val purchased = HeadUpRepository.purchaseItem(requireContext(), item.id)
            val message = when {
                purchased -> R.string.shop_purchase_success
                item.isOwned -> R.string.shop_already_owned
                coins < item.cost -> R.string.shop_not_enough_points
                else -> R.string.shop_purchase_failed
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
