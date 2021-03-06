package me.zsj.pretty_girl.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;

import com.jakewharton.rxbinding.support.v4.widget.RxSwipeRefreshLayout;
import com.jakewharton.rxbinding.support.v7.widget.RecyclerViewScrollEvent;
import com.jakewharton.rxbinding.support.v7.widget.RxRecyclerView;
import com.jakewharton.rxbinding.view.RxView;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;
import com.trello.rxlifecycle.ActivityEvent;
import com.trello.rxlifecycle.components.support.RxAppCompatActivity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import me.zsj.pretty_girl.GirlApi;
import me.zsj.pretty_girl.GirlApiComponent;
import me.zsj.pretty_girl.R;
import me.zsj.pretty_girl.Results;
import me.zsj.pretty_girl.model.GirlData;
import me.zsj.pretty_girl.model.Image;
import me.zsj.pretty_girl.model.PrettyGirl;
import me.zsj.pretty_girl.utils.ConfigurationUtils;
import me.zsj.pretty_girl.utils.NetUtils;
import retrofit.Result;
import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;


public class MainActivity extends RxAppCompatActivity {

    private SwipeRefreshLayout mRefreshLayout;
    private RecyclerView mRecyclerView;
    private Toolbar mToolbar;

    private GirlAdapter mGirlAdapter;
    private List<Image> mImages = new ArrayList<>();

    @Inject GirlApi mGirlApi;
    private int mPage = 1;
    private boolean refreshing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mRefreshLayout = (SwipeRefreshLayout) findViewById(R.id.refreshLayout);
        mRecyclerView = (RecyclerView) findViewById(R.id.recyclerView);
        mToolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(mToolbar);

        GirlApiComponent.Initializer.init().inject(this);

        flyToTop();
        swipeRefresh();
        setupRecyclerView();
        onImageClick();
    }

    private void flyToTop() {
        RxView.clicks(mToolbar)
                .subscribe(aVoid -> {
                    mRecyclerView.smoothScrollToPosition(0);
                });
    }

    private void swipeRefresh() {
        /**
         * 相当于这样的写法
         * mRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
        @Override public void onRefresh() {

        }
        });
         */
        RxSwipeRefreshLayout.refreshes(mRefreshLayout)
                .compose(this.<Void>bindToLifecycle())
                .subscribe(aVoid -> {
                    mPage = 1;
                    refreshing = true;
                    fetchGirlData(true);
                });
    }

    private void setupRecyclerView() {
        mGirlAdapter = new GirlAdapter(this, mImages);
        int spanCount = 2;
        if (ConfigurationUtils.isOrientationPortrait(this)) spanCount = 2;
        else if (ConfigurationUtils.isOrientationLandscape(this)) spanCount = 3;

        final StaggeredGridLayoutManager layoutManager = new StaggeredGridLayoutManager(
                spanCount, StaggeredGridLayoutManager.VERTICAL);
        mRecyclerView.setLayoutManager(layoutManager);
        mRecyclerView.setAdapter(mGirlAdapter);


        /**
        相当于这样的写法
        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
            }
        });*/
        RxRecyclerView.scrollEvents(mRecyclerView)
                .compose(this.<RecyclerViewScrollEvent>bindUntilEvent(ActivityEvent.DESTROY))
                .filter(recyclerViewScrollEvent -> {
                    boolean isBottom = false;
                    if (ConfigurationUtils.isOrientationPortrait(MainActivity.this)) {
                        isBottom = layoutManager.findLastCompletelyVisibleItemPositions(
                                new int[2])[1] >= mImages.size() - 4;
                    }else if (ConfigurationUtils.isOrientationLandscape(MainActivity.this)) {
                        isBottom = layoutManager.findLastCompletelyVisibleItemPositions(
                                new int[3])[2] >= mImages.size() - 4;
                    }
                    return !mRefreshLayout.isRefreshing() && isBottom;
                })
                .subscribe(recyclerViewScrollEvent ->{
                    //这么做的目的是一旦下拉刷新，RxRecyclerView scrollEvents 也会被触发，mPage就会加一
                    //所以要将mPage设为0，这样下拉刷新才能获取第一页的数据
                    if (refreshing) {
                        mPage = 0;
                        refreshing = false;
                    }
                    mPage += 1;
                    mRefreshLayout.setRefreshing(true);
                    fetchGirlData(false);
                });

    }

    private void onImageClick() {
        mGirlAdapter.setOnTouchListener((v, image) ->
                Picasso.with(MainActivity.this).load(image.url).fetch(new Callback() {
                    @Override
                    public void onSuccess() {
                        Intent intent = new Intent(MainActivity.this, PictureActivity.class);
                        intent.putExtra("url", image.url);
                        ActivityOptionsCompat compat =
                                ActivityOptionsCompat.makeSceneTransitionAnimation(MainActivity.this,
                                v, "girl");
                        ActivityCompat.startActivity(MainActivity.this, intent, compat.toBundle());
            }

            @Override
            public void onError() {}
        }));
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        if (!NetUtils.checkNet(this)){
            Snackbar.make(mRecyclerView, "无网络不能获取美女哦!", Snackbar.LENGTH_INDEFINITE)
                    .setAction("知道了", v -> {})
                    .show();
        }
        fetchGirlData(false);
    }

    private void fetchGirlData(final boolean clean) {
        Observable<List<Image>> results = mGirlApi.fetchPrettyGirl(mPage)
                .compose(this.<Result<GirlData>>bindToLifecycle())
                .filter(Results.isSuccess())
                .map(girlDataResult -> girlDataResult.response().body())
                .flatMap(imageFetcher)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .share();

        results.filter(Results.isNull())
                .doOnNext(images -> {
                    if (clean) images.clear();
                })
                .doOnCompleted(() -> mRefreshLayout.setRefreshing(false))
                .subscribe(mGirlAdapter, dataError);
    }

    private final Func1<GirlData, Observable<List<Image>>> imageFetcher = girlData -> {
        List<PrettyGirl> results = girlData.results;
        for (PrettyGirl girl : results) {
            try {
                Bitmap bitmap = Picasso.with(MainActivity.this).load(girl.url)
                        .get();
                Image image = new Image();
                image.width = bitmap.getWidth();
                image.height = bitmap.getHeight();
                image.url = girl.url;
                mImages.add(image);
            }catch (IOException e) {
                e.printStackTrace();
                return Observable.error(e);
            }
        }
        return Observable.just(mImages);
    };

    private Action1<Throwable> dataError = new Action1<Throwable>() {
        @Override
        public void call(Throwable throwable) {
            throwable.printStackTrace();
            mRefreshLayout.setRefreshing(false);
            Snackbar.make(mRecyclerView, throwable.getMessage(), Snackbar.LENGTH_LONG)
                    .show();
        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            Intent intent = new Intent(this, AboutActivity.class);
            startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

}