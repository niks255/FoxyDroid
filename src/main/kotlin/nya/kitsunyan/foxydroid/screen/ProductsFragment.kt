package nya.kitsunyan.foxydroid.screen

import android.database.Cursor
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nya.kitsunyan.foxydroid.R
import nya.kitsunyan.foxydroid.database.CursorOwner
import nya.kitsunyan.foxydroid.database.Database
import nya.kitsunyan.foxydroid.entity.ProductItem
import nya.kitsunyan.foxydroid.service.Connection
import nya.kitsunyan.foxydroid.service.DownloadService
import nya.kitsunyan.foxydroid.utility.RxUtils
import nya.kitsunyan.foxydroid.utility.Utils
import nya.kitsunyan.foxydroid.utility.extension.android.Android
import nya.kitsunyan.foxydroid.utility.extension.android.asSequence
import nya.kitsunyan.foxydroid.utility.extension.resources.getColorFromAttr
import nya.kitsunyan.foxydroid.widget.DividerItemDecoration
import nya.kitsunyan.foxydroid.widget.RecyclerFastScroller

class ProductsFragment(): ScreenFragment(), CursorOwner.Callback {
  companion object {
    private const val EXTRA_SOURCE = "source"

    private const val STATE_CURRENT_SEARCH_QUERY = "currentSearchQuery"
    private const val STATE_CURRENT_SECTION = "currentSection"
    private const val STATE_CURRENT_ORDER = "currentOrder"
    private const val STATE_LAYOUT_MANAGER = "layoutManager"
  }

  enum class Source(val titleResId: Int, val sections: Boolean, val order: Boolean) {
    AVAILABLE(R.string.available, true, true),
    INSTALLED(R.string.installed, false, false),
    UPDATES(R.string.updates, false, false)
  }

  constructor(source: Source): this() {
    arguments = Bundle().apply {
      putString(EXTRA_SOURCE, source.name)
    }
  }

  val source: Source
    get() = requireArguments().getString(EXTRA_SOURCE)!!.let(Source::valueOf)

  private var searchQuery = ""
  private var section: ProductItem.Section = ProductItem.Section.All
  private var order = ProductItem.Order.NAME
  private var currentSearchQuery = ""
  private var currentSection: ProductItem.Section = ProductItem.Section.All
  private val scope = CoroutineScope(Dispatchers.Default)
  private var currentOrder = ProductItem.Order.NAME
  private var layoutManagerState: Parcelable? = null
  private var recyclerView: RecyclerView? = null
  private var updatesHeader: TextView? = null
  private var updatesLayout: RelativeLayout? = null
  private var repositoriesDisposable: Disposable? = null
  private val downloadConnection = Connection(DownloadService::class.java, onBind = { _, binder ->
    lifecycleScope.launch {
      binder.stateSubject.collect {
        updateDownloadState(it)
      }
    }
  })
  private val request: CursorOwner.Request
    get() {
      val searchQuery = searchQuery
      val section = if (source.sections) section else ProductItem.Section.All
      val order = if (source.order) order else ProductItem.Order.NAME
      return when (source) {
        Source.AVAILABLE -> CursorOwner.Request.ProductsAvailable(searchQuery, section, order)
        Source.INSTALLED -> CursorOwner.Request.ProductsInstalled(searchQuery, section, order)
        Source.UPDATES -> CursorOwner.Request.ProductsUpdates(searchQuery, section, order)
      }
    }

  private fun updateDownloadState(state: DownloadService.State?) {
    val status = when (state) {
      is DownloadService.State.Downloading -> ProductAdapter.Status.Downloading(
        state.read,
        state.total
      )
      is DownloadService.State.Pending,
      is DownloadService.State.Connecting,
      is DownloadService.State.Success,
      is DownloadService.State.Error,
      is DownloadService.State.Cancel, null -> null
    }

    if (recyclerView != null) {
      lifecycleScope.launch {
        val adapter = recyclerView?.adapter as ProductsAdapter
        adapter.setStatus(state?.packageName, status)
      }
    }
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    downloadConnection.bind(requireContext())

    val layout = inflater.inflate(R.layout.products, container, false)
    val recyclerView: RecyclerView = layout.findViewById(R.id.products_recycler_view)
    val updateAllButton: Button = layout.findViewById(R.id.update_all)
    val updatesHeader: TextView = layout.findViewById(R.id.updates)
    val updatesLayout: RelativeLayout = layout.findViewById(R.id.updates_layout)

    recyclerView.setHasFixedSize(true)
    recyclerView.itemAnimator = null
    recyclerView.isVerticalScrollBarEnabled = false
    recyclerView.recycledViewPool.setMaxRecycledViews(ProductsAdapter.ViewType.PRODUCT.ordinal, 30)
    val adapter = ProductsAdapter { screenActivity.navigateProduct(it.packageName) }
    recyclerView.adapter = adapter
    recyclerView.addItemDecoration(DividerItemDecoration(recyclerView.context, adapter::configureDivider))
    RecyclerFastScroller(recyclerView)

    if (Android.sdk(22)) {
      updateAllButton.setTextColor(
        updateAllButton.context.getColorFromAttr(android.R.attr.colorBackground))
    }
    updateAllButton.backgroundTintList = updateAllButton.context.
                                                      getColorFromAttr(android.R.attr.colorAccent)
    updateAllButton.setOnClickListener { runUpdate(true) }
    this.recyclerView = recyclerView
    this.updatesLayout = updatesLayout
    this.updatesHeader = updatesHeader
    return layout
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    currentSearchQuery = savedInstanceState?.getString(STATE_CURRENT_SEARCH_QUERY).orEmpty()
    currentSection = savedInstanceState?.getParcelable(STATE_CURRENT_SECTION) ?: ProductItem.Section.All
    currentOrder = savedInstanceState?.getString(STATE_CURRENT_ORDER)
      ?.let(ProductItem.Order::valueOf) ?: ProductItem.Order.NAME
    layoutManagerState = savedInstanceState?.getParcelable(STATE_LAYOUT_MANAGER)

    screenActivity.cursorOwner.attach(this, request)
    repositoriesDisposable = Observable.just(Unit)
      .concatWith(Database.observable(Database.Subject.Repositories))
      .observeOn(Schedulers.io())
      .flatMapSingle { RxUtils.querySingle { Database.RepositoryAdapter.getAll(it) } }
      .map { it.asSequence().map { Pair(it.id, it) }.toMap() }
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe { (recyclerView?.adapter as? ProductsAdapter)?.repositories = it }
  }

  override fun onDestroyView() {
    super.onDestroyView()

    recyclerView = null
    updatesHeader = null
    updatesLayout = null
    downloadConnection.unbind(requireContext())

    screenActivity.cursorOwner.detach(this)
    repositoriesDisposable?.dispose()
    repositoriesDisposable = null
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)

    outState.putString(STATE_CURRENT_SEARCH_QUERY, currentSearchQuery)
    outState.putParcelable(STATE_CURRENT_SECTION, currentSection)
    outState.putString(STATE_CURRENT_ORDER, currentOrder.name)
    (layoutManagerState ?: recyclerView?.layoutManager?.onSaveInstanceState())
      ?.let { outState.putParcelable(STATE_LAYOUT_MANAGER, it) }
  }

  private fun runUpdate(force: Boolean = false) {
    if (ScreenActivity.runUpdate || force) {
      ScreenActivity.runUpdate = false
      val productsAvailableForUpdate: List<ProductItem> = Database.ProductAdapter
        .query(
          installed = true,
          updates = true,
          searchQuery = "",
          section = ProductItem.Section.All,
          order = ProductItem.Order.NAME,
          signal = null
        )
        .use { it.asSequence().map(Database.ProductAdapter::transformItem).toList() }
      if (productsAvailableForUpdate.isNotEmpty()) {
        updateAll(productsAvailableForUpdate)
      }
    }
  }

  override fun onCursorData(request: CursorOwner.Request, cursor: Cursor?) {
    (recyclerView?.adapter as? ProductsAdapter)?.apply {
      this.cursor = cursor
      emptyText = when {
        cursor == null -> ""
        searchQuery.isNotEmpty() -> getString(R.string.no_matching_applications_found)
        else -> when (source) {
          Source.AVAILABLE -> getString(R.string.no_applications_available)
          Source.INSTALLED -> getString(R.string.no_applications_installed)
          Source.UPDATES -> getString(R.string.all_applications_up_to_date)
        }
      }
      if (source == Source.UPDATES && itemCount > 0 &&
          getItemEnumViewType(0) == ProductsAdapter.ViewType.PRODUCT) {
        updatesHeader?.text = resources.getQuantityString(R.plurals.applications_DESC_FORMAT, itemCount, itemCount)
        updatesLayout?.visibility = View.VISIBLE
      } else {
        updatesLayout?.visibility = View.GONE
      }
    }

    layoutManagerState?.let {
      layoutManagerState = null
      recyclerView?.layoutManager?.onRestoreInstanceState(it)
    }

    if (currentSearchQuery != searchQuery || currentSection != section || currentOrder != order) {
      currentSearchQuery = searchQuery
      currentSection = section
      currentOrder = order
      recyclerView?.scrollToPosition(0)
    }
    runUpdate()
  }

  internal fun setSearchQuery(searchQuery: String) {
    if (this.searchQuery != searchQuery) {
      this.searchQuery = searchQuery
      if (view != null) {
        screenActivity.cursorOwner.attach(this, request)
      }
    }
  }

  internal fun setSection(section: ProductItem.Section) {
    if (this.section != section) {
      this.section = section
      if (view != null) {
        screenActivity.cursorOwner.attach(this, request)
      }
    }
  }

  internal fun setOrder(order: ProductItem.Order) {
    if (this.order != order) {
      this.order = order
      if (view != null) {
        screenActivity.cursorOwner.attach(this, request)
      }
    }
  }

  private fun updateAll(productItems: List<ProductItem>) {
    scope.launch {
      productItems.map { productItem ->
        Triple(
          productItem.packageName,
          Database.InstalledAdapter.get(productItem.packageName, null),
          Database.RepositoryAdapter.get(productItem.repositoryId)
        )
      }
        .filter { pair -> pair.second != null && pair.third != null }
        .forEach { installedRepository ->
          run {
            val packageName = installedRepository.first
            val installedItem = installedRepository.second
            val repository = installedRepository.third!!

            val productRepository = Database.ProductAdapter.get(packageName, null)
              .filter { product -> product.repositoryId == repository.id }
              .map { product -> Pair(product, repository) }
            Utils.startUpdate(
              packageName,
              installedItem,
              productRepository,
              downloadConnection
            )
          }
        }
    }
  }
}
