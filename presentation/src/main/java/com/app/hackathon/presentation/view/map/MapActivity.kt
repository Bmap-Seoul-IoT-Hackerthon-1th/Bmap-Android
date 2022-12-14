package com.app.hackathon.presentation.view.map

import android.Manifest
import com.app.hackathon.presentation.widget.extensions.FlutterExtensions.launchLike
import com.app.hackathon.presentation.widget.extensions.FlutterExtensions.launchParking
import com.app.hackathon.presentation.widget.extensions.FlutterExtensions.setupFlutterNavigation
import android.content.Intent
import android.location.Location
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat
import com.app.hackathon.domain.entity.FilterOption
import com.app.hackathon.domain.entity.LotEntity
import com.app.hackathon.presentation.R
import com.app.hackathon.presentation.base.BaseActivity
import com.app.hackathon.presentation.databinding.ActivityMapBinding
import com.app.hackathon.presentation.presenter.map.MapContract
import com.app.hackathon.presentation.presenter.map.MapPresenter
import com.app.hackathon.presentation.view.report.ReportActivity
import com.app.hackathon.presentation.view.search.TextSearchActivity
import com.app.hackathon.presentation.view.search.VoiceSearchActivity
import com.app.hackathon.presentation.widget.Constants
import com.app.hackathon.presentation.widget.Constants.ADDRESS
import com.app.hackathon.presentation.widget.Constants.LATITUDE
import com.app.hackathon.presentation.widget.Constants.LONGITUDE
import com.app.hackathon.presentation.widget.adapter.FilterOptionAdapter
import com.app.hackathon.presentation.widget.extensions.*
import com.app.hackathon.presentation.widget.utils.PreferenceManager
import com.app.hackathon.presentation.widget.utils.PreferenceManager.Companion.COMPANY_KEY
import com.app.hackathon.presentation.widget.utils.PreferenceManager.Companion.HOME_KEY
import com.bumptech.glide.Glide
import com.google.android.gms.location.*
import com.google.gson.Gson
import com.gun0912.tedpermission.rx3.TedPermission
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.*
import com.naver.maps.map.overlay.LocationOverlay
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage
import com.naver.maps.map.util.FusedLocationSource
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject


@AndroidEntryPoint
class MapActivity(override val layoutResId: Int = R.layout.activity_map) :
    BaseActivity<ActivityMapBinding>(), MapContract.View, OnMapReadyCallback {
    @Inject
    lateinit var presenter: MapPresenter

    @Inject
    lateinit var preferenceManager: PreferenceManager

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationSource: FusedLocationSource
    private lateinit var mNaverMap: NaverMap
    private lateinit var activityLauncher: ActivityResultLauncher<Intent>
    private lateinit var filterOptionAdapter: FilterOptionAdapter
    private var activeMarkers: ArrayList<Marker> = arrayListOf()

    companion object {
        val TAG = MapActivity::class.simpleName
    }

    override fun onStart() {
        super.onStart()
//        flutterNavigationTo(UI_INITIALIZE) { call, res ->
//
//        }

        binding.editBtn.setOnClickListener {
            setupFlutterNavigation()
            launchLike(
                onLaunchSearchAddress = { address ->
                    activityLauncher.launch(
                        Intent(
                            this@MapActivity,
                            TextSearchActivity::class.java
                        )
                            .putExtra(LATITUDE, presenter.currentLat)
                            .putExtra(LONGITUDE, presenter.currentLng)
                            .putExtra(ADDRESS, address)
                    )
                },
                onUpdateLikeList = { likes ->
                    with(binding) {
                        val homeInfo = likes[0]
                        val companyInfo = likes[1]

                        if (homeInfo.likeName != "???")
                            preferenceManager.setString(HOME_KEY, homeInfo.likeName)
                        if (companyInfo.likeName != "??????")
                            preferenceManager.setString(COMPANY_KEY, companyInfo.likeName)

                        setHomeCompanyName()
                    }
                }
            )
        }
    }

    override fun initActivity() {
        // ????????? ?????????
        setScreen()

        // overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)

        presenter.onAttach(this)

        setActivityResultLauncher() // ???????????? ?????? ??????
        setScrollTopDetection() // ???????????? ????????? ??????
        setFilterOptions() // ?????? ????????? ??????
        setMap() // ??? ?????? ?????? ??????
        setClickListener() // ?????? ????????? ??????
        binding.setHomeCompanyName() // ???, ?????? ?????? ??????
    }

    private fun setFilterOptions() {
        filterOptionAdapter = FilterOptionAdapter(
            mutableListOf(),
            object : FilterOptionAdapter.OnClickListener {
                override fun onClickItem(item: FilterOption) {
                    // ?????? ????????? ?????? ?????? ??????
                    presenter.filterOptions.map {
                        if (it.optionName == item.optionName) {
                            it.isChecked *= -1
                        }
                        it
                    }

                    filterOptionAdapter.updateData(presenter.filterOptions)
                }
            })
        binding.searchResultContainer.filterOptionRv.adapter = filterOptionAdapter
    }

    private fun setScreen() {
        setStatusBarTransparent()
        // ?????? ???, ??????????????? ?????? ?????? padding ??????
        binding.innerContainer.setPadding(0, statusBarHeight(), 0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.onDetach()
    }


    private fun ActivityMapBinding.setHomeCompanyName() {
        val homeName = preferenceManager.getString(HOME_KEY) ?: ""
        val companyName = preferenceManager.getString(COMPANY_KEY) ?: ""

        if (homeName.isNotEmpty()) {
            homeLotNameTv.text = homeName
            homeLotNameTv.visibility = View.VISIBLE
            homeLotTitleTv.setTextColor(
                ContextCompat.getColor(
                    this@MapActivity,
                    R.color.primaryTextColor
                )
            )
            homeAddTv.visibility = View.GONE
            homeAddBtn.visibility = View.GONE
            homeIconIv.visibility = View.VISIBLE
        }
        if (companyName.isNotEmpty()) {
            companyLotNameTv.text = companyName
            companyLotNameTv.visibility = View.VISIBLE
            companyLotTitleTv.setTextColor(
                ContextCompat.getColor(
                    this@MapActivity,
                    R.color.primaryTextColor
                )
            )
            companyAddTv.visibility = View.GONE
            companyAddBtn.visibility = View.GONE
            companyIconIv.visibility = View.VISIBLE
        }
    }


    private fun setActivityResultLauncher() {
        activityLauncher = registerForActivityResult(
            StartActivityForResult()
        ) { result: ActivityResult ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                // RESULT_OK??? ??? ????????? ??????...

                val searchResult: LotEntity =
                    data?.getSerializableExtra(Constants.SEARCH_RESULT) as LotEntity
                Log.d(TAG, "setActivityResultLauncher: $searchResult")

                // ?????? ?????? ?????? ????????? ??????
                presenter.selectLot(searchResult)

                showSearchResultContainer()
                updateSearchResult(searchResult.parkName) // ?????? ????????? EditText??? ??????
                binding.searchResultContainer.parkName.text = searchResult.parkName // ?????? ?????? ??????????????? ??????
                binding.searchResultContainer.searchQueryTv.text = searchResult.parkName
                Glide.with(this@MapActivity).load(provideRandomParkStateImage(searchResult.parkName)).into(binding.searchResultContainer.lotStateIv)
                binding.searchResultContainer.lotLeftCountTv.text = "${searchResult.nowParkCount} / ${searchResult.maxParkCount}"
                binding.searchResultContainer.lotStateTv.text = searchResult.parkState

                // ????????? ?????? ????????? ??????
                val cameraUpdate = CameraUpdate.scrollTo(
                    LatLng(searchResult.latitude.toDouble(), searchResult.longitude.toDouble())
                ).animate(CameraAnimation.Fly)
                mNaverMap.moveCamera(cameraUpdate)
            }
        }
    }

    private fun updateSearchResult(searchResult: String) {
        // ?????? ?????? ???????????? ??????
        binding.searchResultContainer.searchQueryTv.text = (searchResult)
    }

    private fun showSearchResultContainer() {
        binding.searchResultContainer.root.visibility = View.VISIBLE
        binding.backBtn.visibility = View.VISIBLE
        binding.bottomContainer.visibility = View.GONE
    }

    private fun showSearchContainer() {
        binding.searchResultContainer.root.visibility = View.GONE
        binding.backBtn.visibility = View.GONE
        binding.bottomContainer.visibility = View.VISIBLE
        binding.searchResultContainer.searchQueryTv.text = null
    }

    private fun setClickListener() {
        with(binding) {
            searchVoiceBtn.setOnClickListener {
                activityLauncher.launch(
                    Intent(
                        this@MapActivity,
                        VoiceSearchActivity::class.java
                    )
                )
            }

            // ????????? ??????
            searchEditText.setOnClickListener {
                activityLauncher.launch(
                    Intent(
                        this@MapActivity,
                        TextSearchActivity::class.java
                    )
                        .putExtra(LATITUDE, presenter.currentLat)
                        .putExtra(LONGITUDE, presenter.currentLng)
                )
            }

            searchResultContainer.searchVoiceBtn.setOnClickListener {
                activityLauncher.launch(
                    Intent(
                        this@MapActivity,
                        VoiceSearchActivity::class.java
                    )
                )
            }

            searchResultContainer.searchQueryTv.setOnClickListener {
                activityLauncher.launch(
                    Intent(
                        this@MapActivity,
                        TextSearchActivity::class.java
                    )
                        .putExtra(LATITUDE, presenter.currentLat)
                        .putExtra(LONGITUDE, presenter.currentLng)
                        .putExtra("fromVoice", false)
                )
            }

            backBtn.setOnClickListener {
                showSearchContainer()
            }

            searchResultContainer.filterBtn.setOnClickListener {
                FilterBottomSheetDialog.newInstance(
                    presenter.filterOptions,
                    object : FilterBottomSheetDialog.OnDismissListener {
                        override fun onDismiss(filterList: List<FilterOption>) {
                            presenter.updateFilterOptions(filterList)
                            filterOptionAdapter.updateData(filterList)

                            if (presenter.isFiltered()) {
                                presenter.requestFilteredLotsByLocation(
                                    presenter.lookingLat,
                                    presenter.lookingLng
                                )
                            } else {
                                presenter.requestAroundLotsByLocation(
                                    presenter.currentLat,
                                    presenter.currentLng
                                )
                            }
                        }
                    })
                    .show(supportFragmentManager, FilterBottomSheetDialog::class.java.simpleName)
            }

            // ?????? ??????
            searchResultContainer.lotDetailBtn.setOnClickListener {
                presenter.selectedLot?.let {
                    setupFlutterNavigation()
                    launchParking(Gson().toJson(it), onLaunchReport = {
                        startActivity(Intent(this@MapActivity, ReportActivity::class.java))
                    })
                }
            }
        }
    }

    // ???????????? ?????? ?????? ?????????
    private fun setMap() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        //fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        requestPermissions() // ?????? ??????
        initMapView() // ????????? ??? ?????? ?????? ??????
        setUpdateLocationListener()
    }


    private fun setMapUi() {
        setLocator() // ???????????? UI ??????

        // ???????????? ???????????? ??? ????????? ?????? (subIcon ?????? ???????????? ??????)
        mNaverMap.locationOverlay.icon = OverlayImage.fromResource(R.drawable.img_transparent)

        with(mNaverMap.uiSettings) {
            isCompassEnabled = false
            isScaleBarEnabled = false
            isZoomControlEnabled = false
            isLocationButtonEnabled = false
        }
    }


    // ??? ???????????? UI ??????
    private fun setLocator() {
        presenter.currentMapMode.observe(this) { currentMode ->
            when (currentMode) {
                MapMode.INACTIVE -> {
                    Log.d(TAG, "setLocator: Mode1")
                    loadImage(R.drawable.img_inactive_locator, binding.locatorBtn)
                    //mNaverMap.locationTrackingMode = LocationTrackingMode.None
                    mNaverMap.locationOverlay.subIcon =
                        OverlayImage.fromResource(R.drawable.ic_none_mode_overay)
                }
                MapMode.ACTIVE -> {
                    Log.d(TAG, "setLocator: Mode2")
                    loadImage(R.drawable.img_active_locator, binding.locatorBtn)
                    mNaverMap.locationTrackingMode = LocationTrackingMode.NoFollow
                    mNaverMap.locationOverlay.subIcon =
                        OverlayImage.fromResource(R.drawable.ic_no_follow_mode_overay)
                }
                MapMode.DIRECTION_ACTIVE -> {
                    Log.d(TAG, "setLocator: Mode3")
                    val cameraUpdate = CameraUpdate.scrollTo(
                        LatLng(presenter.currentLat, presenter.currentLng)
                    ).animate(CameraAnimation.Linear)

                    mNaverMap.moveCamera(cameraUpdate) // ?????? ????????? ??????
                    mNaverMap.locationTrackingMode = LocationTrackingMode.NoFollow

                    loadImage(R.drawable.img_active_arrow_locator, binding.locatorBtn)
                    mNaverMap.locationOverlay.subIcon =
                        OverlayImage.fromResource(R.drawable.ic_follow_mode_overay)
                }
            }
        }

        with(binding) {
            locatorBtn.setOnClickListener {
                // ????????? ????????????
                presenter.changeNextMapMode()
            }
        }
    }


    private fun initMapView() {
        // ???????????? ???????????? ????????????
        val fm = supportFragmentManager
        val mapFragment = fm.findFragmentById(R.id.map_fragment) as MapFragment?
            ?: MapFragment.newInstance().also {
                fm.beginTransaction().add(R.id.map_fragment, it).commit()
            }

        mapFragment.getMapAsync(this)
    }


    // ??????????????? ???????????? ???????????? ?????????
    // ????????? ???????????? ???????????? ?????????
    private fun setScrollTopDetection() {
        with(binding) {
            searchScrollView.setOnScrollChangeListener { view, _, _, _, _ ->
                if (!view.canScrollVertically(-1)) {
                    dividerView.visibility = View.GONE
                } else {
                    dividerView.visibility = View.VISIBLE
                }
            }
        }
    }


    // ???????????? ?????? ??????
    private fun requestPermissions() {
        // ?????? ?????? ?????? ?????? ??????
        locationSource =
            FusedLocationSource(this, Constants.LOCATION_PERMISSION_REQUEST_CODE)

        TedPermission.create()
            .setRationaleTitle("???????????? ??????")
            .setRationaleMessage("?????? ????????? ???????????? ?????? ??????????????? ???????????????.") // "we need permission for read contact and find your location"
            .setPermissions(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            .request()
            .subscribe({ tedPermissionResult ->
                if (!tedPermissionResult.isGranted) {
                    showToast("?????? ?????? ????????? ??????????????????.")
                }
            }) { throwable -> Log.e(TAG, throwable.message.toString()) }
    }


    // ???????????? ??????????????? ???????????? ??????
    @UiThread
    override fun onMapReady(naverMap: NaverMap) {
        this.mNaverMap = naverMap

        setMapUi() // ????????? ??? UI ??????

        // ?????? ?????? ?????? ?????? ??????
        setLocationTracking(mNaverMap)

        // ????????? ?????? ??????
//        setTrackingMode(mNaverMap)

        // ???????????? ???????????? ?????? ????????? ????????? ???????????????.
        // ?????? : https://navermaps.github.io/android-map-sdk/reference/com/naver/maps/map/package-summary.html
//        setCameraChangeListener(naverMap)

        // ???????????? ????????? ????????? ?????? ????????? ????????? ???????????????.
        setCameraIdleListener(mNaverMap)

        // ?????? ?????? ?????????
//        setLocationChangeListener(naverMap)

        // ?????? ????????? ???????????? ???????????? ?????? ????????? ???????????? ??????
        if (!checkLocationPermission()) return

        // ????????? ?????? ?????? ????????????
        var currentLocation: Location
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->

            Log.d(TAG, "onMapReady: ????????? ?????? ??????")

            if (location != null) {
                Log.d(TAG, "onMapReady: $location")

                currentLocation = location
                val currentLat = currentLocation.latitude
                val currentLng = currentLocation.longitude


                // ?????? ??????????????? ???????????? ??????????????? false??? ???????????? ????????????. ???????????? true??? ???????????? ????????? ?????? ??????????????? ???????????????.
                // ????????? ???, ?????? ?????? ??????
                mNaverMap.locationOverlay.run {
                    isVisible = true
                    position = LatLng(currentLat, currentLng)
                    circleRadius = 0
                    iconWidth = LocationOverlay.SIZE_AUTO
                    iconHeight = LocationOverlay.SIZE_AUTO
                }

                presenter.updateCurrentLocation(location)
                moveCamera(currentLat, currentLng)
                // ?????? ????????? ???????????? ?????? ????????? ????????? ????????????
                //presenter.requestFilteredLotsByLocation(currentLat, currentLng)
                if (presenter.isFiltered()) {
                    presenter.requestFilteredLotsByLocation(
                        presenter.lookingLat,
                        presenter.lookingLng
                    )
                }else {
                    presenter.requestAroundLotsByLocation(
                        presenter.currentLat,
                        presenter.currentLng
                    )
                }
            }
        }
    }

    private fun moveCamera(currentLat: Double, currentLng: Double) {
        // ????????? ??????????????? ??????
        val cameraUpdate = CameraUpdate.scrollTo(
            LatLng(currentLat, currentLng)
        )
        mNaverMap.moveCamera(cameraUpdate)
    }

    private fun setTrackingMode(naverMap: NaverMap) {
        // NoFollow??? ???????????? ???????????? ???????????? ??????.
        naverMap.locationTrackingMode = LocationTrackingMode.NoFollow
    }

    private fun setLocationChangeListener(naverMap: NaverMap) {
        naverMap.addOnLocationChangeListener { location ->
            // ?????? ????????? ????????? ?????? ?????????
            presenter.setInitialMapMode()
        }
    }

    // ??? ?????? ?????? ??????
    private fun setLocationTracking(naverMap: NaverMap) {
        naverMap.locationSource = locationSource
    }

    private fun setCameraIdleListener(naverMap: NaverMap) {
        naverMap.addOnCameraIdleListener {
            val lat = naverMap.cameraPosition.target.latitude
            val lng = naverMap.cameraPosition.target.longitude

            Log.d(TAG, "setCameraIdleListener: naver map Idle")

            // ?????? ????????? ???????????? ?????? ????????? ????????? ????????????
            presenter.updateLookingLocation(lat, lng)

            if (!presenter.isClickMoving) {

                // presenter.requestAroundLotsByLocation(lat, lng)
                //presenter.requestFilteredLotsByLocation(lat, lng)
                if (presenter.isFiltered()) {
                    presenter.requestFilteredLotsByLocation(
                        lat,
                        lng
                    )
                } else {
                    presenter.requestAroundLotsByLocation(
                        lat,
                        lng
                    )
                }
            } else {
                presenter.isClickMoving = false
            }
        }
    }

    private fun setCameraChangeListener(naverMap: NaverMap) {
        naverMap.addOnCameraChangeListener { reason, animated ->
            Log.i("NaverMap", "????????? ?????? - reson: $reason, animated: $animated")

            if (presenter.currentMapMode.value == MapMode.DIRECTION_ACTIVE) {
                presenter.setInitialMapMode() // ????????? ????????? Locator ????????? ??????
            }
        }
    }

    private fun setUpdateLocationListener() {
        val locationRequest = LocationRequest.create()
        locationRequest.run {
            //priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY //?????? ?????????
            interval = 1000 //1?????? ????????? GPS ??????
        }

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for ((_, location) in locationResult.locations.withIndex()) {
                    Log.d("location: ", "${location.latitude}, ${location.longitude}")
                    presenter.updateCurrentLocation(location)
                }
            }
        }

        //location ?????? ?????? ?????? (locationRequest, locationCallback)
        //???????????? ??????????????? ??????
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.myLooper()
        )
    }

    override fun showLotsOnMap(lotList: List<LotEntity>) {
        // ?????? ????????? ?????? ????????????
        freeActiveMarkers()

        Log.d(TAG, "showLotsOnMap: " + lotList.size)
        lotList.forEach { lot ->
            Log.d(TAG, "showLotsOnMap: " + lot)
            makeMarker(lot)
        }
    }

    private fun makeMarker(lot: LotEntity) {
        val marker = Marker().apply {
            position = LatLng(lot.latitude.toDouble(), lot.longitude.toDouble())
            icon =
                OverlayImage.fromResource(provideRandomUnselectedMarkerImage(lot.parkName))
            width = Marker.SIZE_AUTO
            height = Marker.SIZE_AUTO
            map = mNaverMap
            setOnClickListener {
                Log.d(TAG, "makeMarker: ${lot.latitude}")
                presenter.isClickMoving = true

                // ?????? ????????? ?????? ????????? ????????? ??????
                val cameraUpdate = CameraUpdate.scrollTo(
                    LatLng(
                        lot.latitude.toDouble(), lot.longitude.toDouble()
                    )
                ).animate(CameraAnimation.Linear)
                mNaverMap.moveCamera(cameraUpdate)

                this.subCaptionText = lot.parkName

                // ?????? ?????? ?????? ?????????
                //resetStateMarkers()
                // ????????? ?????? ?????? ??????
                icon = OverlayImage.fromResource(provideRandomSelectedMarkerImage(lot.parkName))

                showSearchResultContainer()
                binding.searchResultContainer.searchQueryTv.text = lot.parkName
                binding.searchResultContainer.parkName.text = lot.parkName
                Glide.with(this@MapActivity).load(provideRandomParkStateImage(lot.parkName)).into(binding.searchResultContainer.lotStateIv)
                binding.searchResultContainer.lotLeftCountTv.text = "${lot.nowParkCount} / ${lot.maxParkCount}"
                binding.searchResultContainer.lotStateTv.text = lot.parkState

                presenter.selectLot(lot)

                Log.d(TAG, "makeMarker: " + Gson().toJson(lot))

                return@setOnClickListener false
            }
        }

        activeMarkers.add(marker)
    }


    // ???????????? ?????????????????? ????????? ???????????? ??????
    private fun freeActiveMarkers() {
        for (activeMarker in activeMarkers) {
            activeMarker.map = null
        }

        activeMarkers.clear()
    }

    private fun isShowSearchResult(): Boolean {
        return binding.searchResultContainer.root.visibility == View.VISIBLE
    }

    // ?????? -> ?????? ??????
//    private fun getAddress(lat: Double, lng: Double): String {
//        val geoCoder = Geocoder(this, Locale.KOREA)
//        val address: ArrayList<Address>
//        var addressResult = "????????? ?????? ??? ??? ????????????."
//        try {
//            //????????? ??????????????? ????????? ?????? ????????? ?????? ?????? ?????????
//            //???????????? ?????? ??????????????? ????????? ????????????????????? ??????????????? ???????????? ?????? ???????????? ??????
//            address = geoCoder.getFromLocation(lat, lng, 1) as ArrayList<Address>
//            if (address.size > 0) {
//                // ?????? ????????????
//                val currentLocationAddress = address[0].getAddressLine(0)
//                    .toString()
//                addressResult = currentLocationAddress
//
//            }
//
//        } catch (e: IOException) {
//            e.printStackTrace()
//        }
//        return addressResult
//    }


    override fun onBackPressed() {
        if (isShowSearchResult()) {
            showSearchContainer()
        } else {
            super.onBackPressed()
        }
    }
}