package com.hoc.drinkshop

import android.os.Bundle
import android.support.annotation.IdRes
import android.support.v7.app.AppCompatActivity
import android.support.v7.recyclerview.extensions.ListAdapter
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.helper.ItemTouchHelper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import com.hoc.drinkshop.DrinkActivity.Companion.DRINK
import com.hoc.drinkshop.DrinkAdapter.Companion.decimalFormatPrice
import com.hoc.drinkshop.DrinkAdapter.Companion.diffCallback
import com.hoc.drinkshop.MainActivity.Companion.USER
import com.miguelcatalan.materialsearchview.MaterialSearchView
import com.squareup.picasso.Picasso
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.processors.PublishProcessor
import io.reactivex.rxkotlin.addTo
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_favorites.*
import kotlinx.android.synthetic.main.fav_item_layout.view.*
import kotlinx.coroutines.experimental.Job
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.info
import org.jetbrains.anko.startActivity
import org.jetbrains.anko.toast
import org.koin.android.ext.android.inject
import retrofit2.HttpException
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

fun MaterialSearchView.textChange(): Flowable<String> {
    val processor = PublishProcessor.create<String>()
    setOnQueryTextListener(object : MaterialSearchView.OnQueryTextListener {
        override fun onQueryTextSubmit(query: String?) = true

        override fun onQueryTextChange(newText: String?) =
                newText?.let {
                    processor.onNext(it)
                    true
                } == true
    })
    return processor.onBackpressureLatest()
}

class FavoritesAdapter(private val onClickListener: (Drink, Int) -> Unit) : ListAdapter<Drink, FavoritesAdapter.ViewHolder>(diffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(parent inflate R.layout.fav_item_layout)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onClickListener)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textName = itemView.textName
        private val imageView = itemView.imageView
        private val cardView = itemView.cardView
        private val textPrice = itemView.textPrice
        private val imageFav = itemView.imageFav
        val swipableView = itemView.background_layout!!

        fun bind(item: Drink?, onClickListener: (Drink, Int) -> Unit) = item?.let { drink ->
            textName.text = drink.name
            textPrice.text = itemView.context.getString(R.string.price, decimalFormatPrice.format(drink.price))
            Picasso.with(imageView.context)
                    .load(drink.imageUrl)
                    .fit()
                    .placeholder(R.drawable.ic_image_black_24dp)
                    .error(R.drawable.ic_image_black_24dp)
                    .into(imageView)

            val listener = View.OnClickListener { onClickListener(drink, it.id) }
            cardView.setOnClickListener(listener)
            imageFav.setOnClickListener(listener)

            imageFav.setImageResource(R.drawable.ic_favorite_black_24dp)
        }
    }
}

class FavoritesActivity : AppCompatActivity(), AnkoLogger {
    private val apiService by inject<ApiService>()
    private val retrofit by inject<Retrofit>()

    private val favoritesAdapter = FavoritesAdapter(::onDrinkClick)
    private lateinit var drinks: MutableList<Drink>
    private lateinit var user: User

    private val parentJob = Job()
    private val compositeDisposable = CompositeDisposable()

    private fun onDrinkClick(drink: Drink, @IdRes clickId: Int) {
        when (clickId) {
            R.id.cardView -> startActivity<AddToCartActivity>(DRINK to drink)
            R.id.imageFav -> removeFromFavorites(user.phone, drink)
        }
    }


    private fun removeFromFavorites(phone: String, drink: Drink) {
        compositeDisposable += apiService.unstar(phone, drink.id)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeBy(
                        onError = {
                            when (it) {
                                is HttpException -> it.response().errorBody()?.let {
                                    retrofit.parseResultErrorMessage(it)
                                            .let(::toast)
                                }
                                else -> toast(it.message ?: "An error occurred")
                            }
                        },
                        onNext = { d ->
                            drinks.indexOfFirst { it.id == d.id }
                                    .takeIf { it >= 0 }
                                    ?.let {
                                        drinks.removeAt(it)
                                        favoritesAdapter.notifyItemRemoved(it)
                                        toast("Removed from favorites successfully")
                                    }
                        }
                )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)
        setSupportActionBar(toolbar)
        supportActionBar?.run {
            title = "My favorite drinks"
            setDisplayHomeAsUpEnabled(true)
        }

        user = intent.getParcelableExtra(USER)

        recycler_favorites.run {
            layoutManager = LinearLayoutManager(this@FavoritesActivity)
            adapter = favoritesAdapter
            setHasFixedSize(true)
            ItemTouchHelper(ItemTouchHelperCallback(::onSwiped)).attachToRecyclerView(this)
        }

        search_view.textChange()
                .throttleFirst(300, TimeUnit.MILLISECONDS)
                .distinctUntilChanged()
                .map { it.toLowerCase() }
                .switchMap { queryString ->
                    toast("onNext: $queryString")
                    apiService
                            .getDrinksFlowable(
                                    name = if (queryString.isBlank()) null else queryString,
                                    phone = user.phone
                            )
                            .subscribeOn(Schedulers.io())
                }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeBy(
                        onError = {
                            when (it) {
                                is HttpException -> it.response().errorBody()?.let {
                                    retrofit.parseResultErrorMessage(it)
                                            .let(::toast)
                                }
                                else -> toast(it.message ?: "An error occurred")
                            }
                        },
                        onNext = {
                            info("onNext: ${it.size}")
                            drinks = it.toMutableList()
                            favoritesAdapter.submitList(drinks)
                        }
                )
                .addTo(compositeDisposable)
        search_view.setQuery("", false)
    }


    private fun onSwiped(position: Int) {
        toast("onSwiped $position")
    }

    override fun onBackPressed() {
        if (search_view.isSearchOpen) {
            search_view.closeSearch()
        } else {
            super.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_favorites, menu)
        menu?.findItem(R.id.action_search).let { search_view.setMenuItem(it) }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        return when (item?.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        parentJob.cancel()
        compositeDisposable.clear()
    }
}

