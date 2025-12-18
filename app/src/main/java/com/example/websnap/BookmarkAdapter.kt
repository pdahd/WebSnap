package com.example.websnap

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.websnap.databinding.ItemBookmarkBinding

/**
 * 书签列表适配器
 */
class BookmarkAdapter(
    private val onItemClick: (Bookmark) -> Unit,
    private val onDeleteClick: (Bookmark, Int) -> Unit
) : RecyclerView.Adapter<BookmarkAdapter.BookmarkViewHolder>() {

    private val bookmarks: MutableList<Bookmark> = mutableListOf()

    /**
     * 更新书签列表数据
     */
    fun submitList(newBookmarks: List<Bookmark>) {
        val diffCallback = BookmarkDiffCallback(bookmarks, newBookmarks)
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        bookmarks.clear()
        bookmarks.addAll(newBookmarks)

        diffResult.dispatchUpdatesTo(this)
    }

    /**
     * 移除指定位置的书签（带动画）
     */
    fun removeAt(position: Int) {
        if (position >= 0 && position < bookmarks.size) {
            bookmarks.removeAt(position)
            notifyItemRemoved(position)
            // 更新后续项的位置
            notifyItemRangeChanged(position, bookmarks.size - position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val binding = ItemBookmarkBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BookmarkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        holder.bind(bookmarks[position])
    }

    override fun getItemCount(): Int = bookmarks.size

    /**
     * ViewHolder
     */
    inner class BookmarkViewHolder(
        private val binding: ItemBookmarkBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // 点击整行 → 打开书签
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(bookmarks[position])
                }
            }

            // 点击删除按钮
            binding.buttonDelete.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onDeleteClick(bookmarks[position], position)
                }
            }
        }

        fun bind(bookmark: Bookmark) {
            binding.textViewTitle.text = bookmark.title.ifBlank { bookmark.url }
            binding.textViewUrl.text = bookmark.url
        }
    }

    /**
     * DiffUtil 回调，用于高效更新列表
     */
    private class BookmarkDiffCallback(
        private val oldList: List<Bookmark>,
        private val newList: List<Bookmark>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            // 以 URL 作为唯一标识
            return oldList[oldItemPosition].url == newList[newItemPosition].url
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}
