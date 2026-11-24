//
//  LandmarkLocationPickerView.swift
//  LookSeeProto
//
//  Reusable Uber Eats-style center-pin map picker & mini-map preview card
//  for confirming upload locations and relocating existing landmarks.
//

import SwiftUI
import MapKit
import CoreLocation
import Combine

// MARK: - Picker Mode

enum LandmarkLocationPickerMode: Equatable {
    /// Used during new landmark upload before submission.
    case upload
    /// Used when editing an existing landmark's coordinates in BusinessLandmarkDetailView.
    /// When `isTrained` is true and the pin moves farther than `farMoveThresholdMeters`,
    /// a confirmation alert lets the owner hide the map pin until overnight retraining finishes.
    case editExisting(isTrained: Bool)
}

// MARK: - Zero-Rerender Camera Settle Tracker

@MainActor
private final class MapCameraTracker: ObservableObject {
    var latestRegion: MKCoordinateRegion?
    private var settleWorkItem: DispatchWorkItem?

    func scheduleSettle(delay: TimeInterval = 0.18, action: @escaping @MainActor () -> Void) {
        settleWorkItem?.cancel()
        let item = DispatchWorkItem {
            Task { @MainActor in
                action()
            }
        }
        settleWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    func cancel() {
        settleWorkItem?.cancel()
        settleWorkItem = nil
    }
}

// MARK: - Live Address Search Helper

final class LocationSearchModel: NSObject, ObservableObject, MKLocalSearchCompleterDelegate {
    @Published var query: String = "" {
        didSet {
            let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty {
                results = []
                completer.queryFragment = ""
            } else {
                completer.queryFragment = trimmed
            }
        }
    }
    @Published var results: [MKLocalSearchCompletion] = []

    private let completer = MKLocalSearchCompleter()

    override init() {
        super.init()
        completer.delegate = self
        completer.resultTypes = [.address, .pointOfInterest]
    }

    func updateRegionBias(_ region: MKCoordinateRegion) {
        completer.region = region
    }

    func resolve(_ completion: MKLocalSearchCompletion) async -> CLLocationCoordinate2D? {
        let request = MKLocalSearch.Request(completion: completion)
        let search = MKLocalSearch(request: request)
        do {
            let response = try await search.start()
            return response.mapItems.first?.placemark.coordinate
        } catch {
            return nil
        }
    }

    nonisolated func completerDidUpdateResults(_ completer: MKLocalSearchCompleter) {
        let updated = Array(completer.results.prefix(6))
        Task { @MainActor in
            self.results = updated
        }
    }

    nonisolated func completer(_ completer: MKLocalSearchCompleter, didFailWithError error: Error) {
        Task { @MainActor in
            self.results = []
        }
    }
}

// MARK: - Reverse Geocoding Helper

@MainActor
final class ReverseGeocodeHelper: ObservableObject {
    @Published var primaryAddress: String = ""
    @Published var secondaryAddress: String = ""
    @Published var isGeocoding: Bool = false

    private let geocoder = CLGeocoder()
    private var lastGeocodedCoordinate: CLLocationCoordinate2D?

    func lookup(coordinate: CLLocationCoordinate2D, force: Bool = false) {
        if !force, let last = lastGeocodedCoordinate {
            let lastLoc = CLLocation(latitude: last.latitude, longitude: last.longitude)
            let newLoc = CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
            if lastLoc.distance(from: newLoc) < 3.0 && !primaryAddress.isEmpty {
                return
            }
        }

        geocoder.cancelGeocode()
        isGeocoding = true
        lastGeocodedCoordinate = coordinate

        let location = CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
        geocoder.reverseGeocodeLocation(location) { [weak self] placemarks, _ in
            Task { @MainActor in
                guard let self else { return }
                self.isGeocoding = false

                guard let place = placemarks?.first else {
                    self.primaryAddress = "Pinned Location"
                    self.secondaryAddress = String(
                        format: "%.5f, %.5f",
                        coordinate.latitude,
                        coordinate.longitude
                    )
                    return
                }

                let streetParts = [place.subThoroughfare, place.thoroughfare]
                    .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
                    .filter { !$0.isEmpty }
                let street = streetParts.joined(separator: " ")

                let name = place.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                if !street.isEmpty {
                    self.primaryAddress = street
                } else if !name.isEmpty {
                    self.primaryAddress = name
                } else {
                    self.primaryAddress = "Pinned Location"
                }

                let cityState = [place.locality, place.administrativeArea]
                    .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
                    .filter { !$0.isEmpty }
                    .joined(separator: ", ")

                let postal = place.postalCode?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                let fullSecondary = [cityState, postal]
                    .filter { !$0.isEmpty }
                    .joined(separator: " ")

                self.secondaryAddress = fullSecondary.isEmpty
                    ? String(format: "%.5f, %.5f", coordinate.latitude, coordinate.longitude)
                    : fullSecondary
            }
        }
    }
}

// MARK: - Reusable Mini-Map Preview Card

struct LandmarkLocationPreviewCard: View {
    let latitude: Double?
    let longitude: Double?
    var title: String = "Does this location look right?"
    var subtitle: String = "Drag the pin if you recorded indoors or walked away."
    var buttonTitle: String = "Adjust Pin"
    var badgeText: String? = nil
    var badgeColor: Color = .green
    var accuracyMeters: Double? = nil
    var showEnableLocationButton: Bool = false
    var isDisabled: Bool = false
    var onEnableLocation: (() -> Void)? = nil
    var onAdjustTapped: () -> Void

    @StateObject private var geocodeHelper = ReverseGeocodeHelper()
    @State private var cameraPosition: MapCameraPosition = .automatic

    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)

    private var coordinate: CLLocationCoordinate2D? {
        guard let lat = latitude, let lon = longitude,
              lat.isFinite, lon.isFinite,
              (-90.0...90.0).contains(lat),
              (-180.0...180.0).contains(lon),
              !(lat == 0 && lon == 0) else {
            return nil
        }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    private var coordinateKey: String {
        guard let c = coordinate else { return "none" }
        return String(format: "%.6f,%.6f", c.latitude, c.longitude)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.system(size: 16, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)

                    Text(subtitle)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 8)

                if let badgeText, !badgeText.isEmpty {
                    Text(badgeText)
                        .font(.system(size: 11, weight: .bold, design: .rounded))
                        .foregroundStyle(badgeColor)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 5)
                        .background(badgeColor.opacity(0.14), in: Capsule())
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
            .padding(.bottom, 12)

            // Mini-map preview
            if let coordinate {
                Button {
                    guard !isDisabled else { return }
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    onAdjustTapped()
                } label: {
                    ZStack {
                        Map(position: $cameraPosition, interactionModes: []) {
                            EmptyMapContent()
                        }
                        .mapStyle(.standard(elevation: .flat, pointsOfInterest: .including([.store, .restaurant, .cafe, .park, .museum])))
                        .allowsHitTesting(false)

                        // Center pin graphic
                        VStack(spacing: 0) {
                            ZStack {
                                Circle()
                                    .fill(primaryColor)
                                    .frame(width: 34, height: 34)
                                    .shadow(color: primaryColor.opacity(0.45), radius: 6, x: 0, y: 3)

                                Image(systemName: "mappin")
                                    .font(.system(size: 16, weight: .bold))
                                    .foregroundStyle(.white)
                            }

                            Rectangle()
                                .fill(primaryColor)
                                .frame(width: 3, height: 10)
                                .offset(y: -1)

                            Ellipse()
                                .fill(.black.opacity(0.32))
                                .frame(width: 12, height: 4)
                                .offset(y: -2)
                        }
                        .offset(y: -18)

                        // Floating "Adjust Pin" pill
                        if !isDisabled {
                            VStack {
                                HStack {
                                    Spacer()
                                    HStack(spacing: 5) {
                                        Image(systemName: "hand.draw.fill")
                                            .font(.system(size: 11, weight: .bold))
                                        Text(buttonTitle)
                                            .font(.system(size: 12, weight: .bold, design: .rounded))
                                    }
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 7)
                                    .background(Color.black.opacity(0.72), in: Capsule())
                                    .padding(10)
                                }
                                Spacer()
                            }
                        }
                    }
                    .frame(height: 165)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(Color.primary.opacity(0.08), lineWidth: 1)
                    }
                    .padding(.horizontal, 16)
                }
                .buttonStyle(.plain)
                .disabled(isDisabled)
                .onAppear {
                    syncCamera(to: coordinate)
                    geocodeHelper.lookup(coordinate: coordinate, force: true)
                }
                .onChange(of: coordinateKey) { _, _ in
                    syncCamera(to: coordinate)
                    geocodeHelper.lookup(coordinate: coordinate, force: true)
                }

                // Address + Coordinates footer
                HStack(alignment: .center, spacing: 12) {
                    Image(systemName: "location.fill")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(primaryColor)
                        .frame(width: 34, height: 34)
                        .background(primaryColor.opacity(0.12), in: Circle())

                    VStack(alignment: .leading, spacing: 2) {
                        if geocodeHelper.isGeocoding && geocodeHelper.primaryAddress.isEmpty {
                            Text("Finding address…")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(.secondary)
                        } else {
                            Text(geocodeHelper.primaryAddress.isEmpty ? "Pinned Location" : geocodeHelper.primaryAddress)
                                .font(.system(size: 14, weight: .bold, design: .rounded))
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                        }

                        HStack(spacing: 6) {
                            Text(String(format: "%.5f, %.5f", coordinate.latitude, coordinate.longitude))
                                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                                .foregroundStyle(.secondary)

                            if let accuracyMeters, accuracyMeters > 0 {
                                Text("• ±\(Int(accuracyMeters))m")
                                    .font(.system(size: 11, weight: .medium))
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }

                    Spacer()

                    if !isDisabled {
                        Button {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            onAdjustTapped()
                        } label: {
                            Text("Edit")
                                .font(.system(size: 13, weight: .bold, design: .rounded))
                                .foregroundStyle(primaryColor)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 7)
                                .background(primaryColor.opacity(0.12), in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(16)
            } else {
                // No GPS coordinates yet
                VStack(spacing: 12) {
                    HStack(spacing: 12) {
                        Image(systemName: "location.slash.fill")
                            .font(.system(size: 20))
                            .foregroundStyle(.orange)
                            .frame(width: 40, height: 40)
                            .background(Color.orange.opacity(0.14), in: Circle())

                        VStack(alignment: .leading, spacing: 2) {
                            Text("Location not set yet")
                                .font(.system(size: 15, weight: .bold, design: .rounded))
                                .foregroundStyle(.primary)
                            Text("Enable GPS or drop a pin manually on the map.")
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }

                    HStack(spacing: 10) {
                        if showEnableLocationButton, let onEnableLocation {
                            Button {
                                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                onEnableLocation()
                            } label: {
                                Text("Enable GPS")
                                    .font(.system(size: 14, weight: .bold, design: .rounded))
                                    .foregroundStyle(primaryColor)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 11)
                                    .background(primaryColor.opacity(0.12))
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            }
                            .buttonStyle(.plain)
                            .disabled(isDisabled)
                        }

                        Button {
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            onAdjustTapped()
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "mappin.and.ellipse")
                                Text("Drop Pin on Map")
                            }
                            .font(.system(size: 14, weight: .bold, design: .rounded))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 11)
                            .background(primaryColor)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                        .buttonStyle(.plain)
                        .disabled(isDisabled)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
            }
        }
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
    }

    private func syncCamera(to coord: CLLocationCoordinate2D) {
        cameraPosition = .region(
            MKCoordinateRegion(
                center: coord,
                span: MKCoordinateSpan(latitudeDelta: 0.0022, longitudeDelta: 0.0022)
            )
        )
    }
}

// MARK: - Full-Screen Uber Eats-Style Center-Pin Map Sheet

struct LandmarkLocationPickerSheet: View {
    let initialLatitude: Double?
    let initialLongitude: Double?
    var userLatitude: Double? = nil
    var userLongitude: Double? = nil
    var mode: LandmarkLocationPickerMode = .upload
    var navigationTitle: String = "Set Landmark Location"
    var confirmButtonTitle: String = "Confirm Location"
    var isSaving: Bool = false
    var saveErrorMessage: String? = nil

    /// Called when the user confirms coordinates.
    /// `overrideVisibleOnMap`: `nil` means keep current visibility; `false` means hide pin on map until retraining finishes.
    let onConfirm: (_ latitude: Double, _ longitude: Double, _ overrideVisibleOnMap: Bool?) -> Void

    @Environment(\.dismiss) private var dismiss

    @StateObject private var searchModel = LocationSearchModel()
    @StateObject private var geocodeHelper = ReverseGeocodeHelper()
    @StateObject private var cameraTracker = MapCameraTracker()

    @State private var cameraPosition: MapCameraPosition
    @State private var centerCoordinate: CLLocationCoordinate2D
    @State private var isDragging = false
    @State private var isSatelliteMode = false
    @State private var showSearchDropdown = false
    @State private var hasHandledInitialCameraEvent = false
    @State private var showFarMoveAlert = false

    @FocusState private var isSearchFocused: Bool

    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)
    /// Moves beyond 1 mile (~1,609m) on an already-trained landmark warn about re-clustering & overnight retraining.
    private let farMoveThresholdMeters: Double = 1609.34

    private var originCoordinate: CLLocationCoordinate2D? {
        guard let lat = initialLatitude, let lon = initialLongitude,
              lat.isFinite, lon.isFinite,
              (-90.0...90.0).contains(lat),
              (-180.0...180.0).contains(lon),
              !(lat == 0 && lon == 0) else {
            return nil
        }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    private var userCoordinate: CLLocationCoordinate2D? {
        guard let lat = userLatitude, let lon = userLongitude,
              lat.isFinite, lon.isFinite,
              (-90.0...90.0).contains(lat),
              (-180.0...180.0).contains(lon),
              !(lat == 0 && lon == 0) else {
            return nil
        }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    init(
        initialLatitude: Double?,
        initialLongitude: Double?,
        userLatitude: Double? = nil,
        userLongitude: Double? = nil,
        mode: LandmarkLocationPickerMode = .upload,
        navigationTitle: String = "Set Landmark Location",
        confirmButtonTitle: String = "Confirm Location",
        isSaving: Bool = false,
        saveErrorMessage: String? = nil,
        onConfirm: @escaping (_ latitude: Double, _ longitude: Double, _ overrideVisibleOnMap: Bool?) -> Void
    ) {
        self.initialLatitude = initialLatitude
        self.initialLongitude = initialLongitude
        self.userLatitude = userLatitude
        self.userLongitude = userLongitude
        self.mode = mode
        self.navigationTitle = navigationTitle
        self.confirmButtonTitle = confirmButtonTitle
        self.isSaving = isSaving
        self.saveErrorMessage = saveErrorMessage
        self.onConfirm = onConfirm

        let fallback = CLLocationCoordinate2D(latitude: 41.8077, longitude: -72.2540)
        let startCoord: CLLocationCoordinate2D = {
            if let lat = initialLatitude, let lon = initialLongitude,
               lat.isFinite, lon.isFinite,
               (-90.0...90.0).contains(lat), (-180.0...180.0).contains(lon),
               !(lat == 0 && lon == 0) {
                return CLLocationCoordinate2D(latitude: lat, longitude: lon)
            }
            if let lat = userLatitude, let lon = userLongitude,
               lat.isFinite, lon.isFinite,
               (-90.0...90.0).contains(lat), (-180.0...180.0).contains(lon),
               !(lat == 0 && lon == 0) {
                return CLLocationCoordinate2D(latitude: lat, longitude: lon)
            }
            return fallback
        }()

        let span = (initialLatitude != nil || userLatitude != nil)
            ? MKCoordinateSpan(latitudeDelta: 0.0025, longitudeDelta: 0.0025)
            : MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)

        _centerCoordinate = State(initialValue: startCoord)
        _cameraPosition = State(
            initialValue: .region(MKCoordinateRegion(center: startCoord, span: span))
        )
    }

    private var movedDistanceMeters: Double {
        guard let origin = originCoordinate else { return 0 }
        let startLoc = CLLocation(latitude: origin.latitude, longitude: origin.longitude)
        let currentLoc = CLLocation(latitude: centerCoordinate.latitude, longitude: centerCoordinate.longitude)
        return startLoc.distance(from: currentLoc)
    }

    private var isFarMoveOnTrainedLandmark: Bool {
        if case .editExisting(let isTrained) = mode, isTrained, originCoordinate != nil {
            return movedDistanceMeters >= farMoveThresholdMeters
        }
        return false
    }

    private var formattedDistanceMoved: String? {
        guard originCoordinate != nil, movedDistanceMeters >= 5 else { return nil }
        let feet = movedDistanceMeters * 3.28084
        if feet < 1000 {
            return "Moved \(Int(feet.rounded())) ft"
        }
        let miles = movedDistanceMeters / 1609.34
        return String(format: "Moved %.2f mi", miles)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                // Layer 1: Full-bleed Map (ignores keyboard; zero @State mutations mid-pan)
                Map(position: $cameraPosition) {
                    UserAnnotation()

                    if let origin = originCoordinate, movedDistanceMeters >= 15 {
                        Annotation("Original", coordinate: origin) {
                            Circle()
                                .fill(Color.secondary.opacity(0.45))
                                .frame(width: 12, height: 12)
                                .overlay(Circle().stroke(.white, lineWidth: 2))
                                .shadow(radius: 2)
                        }
                    }
                }
                .mapStyle(isSatelliteMode ? .hybrid(elevation: .flat) : .standard(elevation: .flat))
                .onMapCameraChange(frequency: .continuous) { context in
                    cameraTracker.latestRegion = context.region

                    if !hasHandledInitialCameraEvent {
                        hasHandledInitialCameraEvent = true
                        searchModel.updateRegionBias(context.region)
                        return
                    }

                    if !isDragging {
                        withAnimation(.spring(response: 0.22, dampingFraction: 0.72)) {
                            isDragging = true
                        }
                    }

                    cameraTracker.scheduleSettle(delay: 0.18) {
                        let settledRegion = cameraTracker.latestRegion ?? context.region
                        centerCoordinate = settledRegion.center
                        searchModel.updateRegionBias(settledRegion)
                        withAnimation(.spring(response: 0.28, dampingFraction: 0.62)) {
                            isDragging = false
                        }
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        geocodeHelper.lookup(coordinate: settledRegion.center)
                    }
                }
                .ignoresSafeArea(edges: .bottom)
                .ignoresSafeArea(.keyboard)

                // Layer 2: Center-Anchored Floating Pin (exact match with map center)
                centerPinView
                    .allowsHitTesting(false)
                    .ignoresSafeArea(.keyboard)
                    .opacity(isSearchFocused ? 0.2 : 1)

                // Layer 3: Floating Controls & Bottom Confirmation Card (hides cleanly while searching)
                VStack(spacing: 0) {
                    Spacer()

                    HStack {
                        Spacer()
                        floatingMapControls
                            .padding(.trailing, 16)
                            .padding(.bottom, 14)
                    }

                    bottomConfirmationCard
                }
                .ignoresSafeArea(.keyboard)
                .opacity(isSearchFocused ? 0 : 1)
                .allowsHitTesting(!isSearchFocused)
                .animation(.easeOut(duration: 0.18), value: isSearchFocused)

                // Layer 3.5: Scrim behind search results (tap anywhere on dimmed map to dismiss keyboard)
                if isSearchFocused {
                    Color.black.opacity(0.32)
                        .ignoresSafeArea()
                        .onTapGesture {
                            dismissSearch()
                        }
                        .transition(.opacity)
                }

                // Layer 4: Keyboard-Safe Search Bar + Scrollable Dropdown
                // GeometryReader sits strictly between the nav bar and the keyboard.
                GeometryReader { geo in
                    VStack(spacing: 8) {
                        searchBar

                        if showSearchDropdown && !searchModel.results.isEmpty {
                            ViewThatFits(in: .vertical) {
                                searchResultsList
                                ScrollView {
                                    searchResultsList
                                }
                                .scrollDismissesKeyboard(.interactively)
                            }
                            .frame(maxHeight: max(120, geo.size.height - 84))
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .overlay {
                                RoundedRectangle(cornerRadius: 16, style: .continuous)
                                    .stroke(Color.primary.opacity(0.10), lineWidth: 1)
                            }
                            .shadow(color: .black.opacity(0.18), radius: 14, x: 0, y: 6)
                        }

                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .frame(width: geo.size.width, height: geo.size.height, alignment: .top)
                }
            }
            .animation(.easeOut(duration: 0.18), value: isSearchFocused)
            .navigationTitle(navigationTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                    .disabled(isSaving)
                }
            }
            .onAppear {
                geocodeHelper.lookup(coordinate: centerCoordinate, force: true)
            }
            .onDisappear {
                cameraTracker.cancel()
            }
            .alert("Moving to a New Area", isPresented: $showFarMoveAlert) {
                Button("Move & Hide Pin Until Ready") {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    onConfirm(centerCoordinate.latitude, centerCoordinate.longitude, false)
                }
                Button("Move & Keep on Map") {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    onConfirm(centerCoordinate.latitude, centerCoordinate.longitude, nil)
                }
                Button("Cancel", role: .cancel) { }
            } message: {
                Text("This location is over 1 mile from the current pin. Moving into a new cluster triggers automatic re-clustering and overnight model retraining.\n\nHiding the map pin until retraining finishes prevents users from visiting before AR scanning works in the new area.")
            }
        }
    }

    // MARK: - Center Floating Pin

    private var centerPinView: some View {
        ZStack {
            Circle()
                .stroke(primaryColor.opacity(isDragging ? 0.55 : 0.0), lineWidth: 2)
                .frame(width: isDragging ? 26 : 10, height: isDragging ? 26 : 10)

            Ellipse()
                .fill(Color.black.opacity(isDragging ? 0.22 : 0.38))
                .frame(width: isDragging ? 10 : 16, height: isDragging ? 4 : 6)

            VStack(spacing: 0) {
                ZStack {
                    Circle()
                        .fill(primaryColor)
                        .frame(width: 44, height: 44)
                        .overlay(Circle().stroke(.white, lineWidth: 3))
                        .shadow(color: .black.opacity(0.28), radius: isDragging ? 12 : 6, x: 0, y: isDragging ? 10 : 4)

                    Image(systemName: "mappin")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(.white)
                }
                .scaleEffect(isDragging ? 1.08 : 1.0)

                Capsule()
                    .fill(primaryColor)
                    .frame(width: 4, height: 14)
                    .offset(y: -2)
            }
            .offset(y: isDragging ? -42 : -28)
        }
    }

    // MARK: - Search Bar & Results

    private var searchBar: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(primaryColor)

            TextField("Search address or place…", text: $searchModel.query)
                .font(.system(size: 15, weight: .medium, design: .rounded))
                .focused($isSearchFocused)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .onChange(of: isSearchFocused) { _, focused in
                    withAnimation(.easeOut(duration: 0.18)) {
                        showSearchDropdown = focused
                    }
                }
                .onSubmit {
                    if let first = searchModel.results.first {
                        selectSearchResult(first)
                    } else {
                        dismissSearch()
                    }
                }

            if !searchModel.query.isEmpty {
                Button {
                    searchModel.query = ""
                    searchModel.results = []
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Color.primary.opacity(0.10), lineWidth: 1)
        }
        .shadow(color: .black.opacity(0.14), radius: 12, x: 0, y: 4)
    }

    private var searchResultsList: some View {
        VStack(spacing: 0) {
            ForEach(Array(searchModel.results.enumerated()), id: \.offset) { index, completion in
                Button {
                    selectSearchResult(completion)
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "mappin.circle.fill")
                            .font(.system(size: 18))
                            .foregroundStyle(primaryColor)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(completion.title)
                                .font(.system(size: 14, weight: .bold, design: .rounded))
                                .foregroundStyle(.primary)
                                .lineLimit(1)

                            if !completion.subtitle.isEmpty {
                                Text(completion.subtitle)
                                    .font(.system(size: 12, weight: .medium))
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                        }

                        Spacer()
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                if index < searchModel.results.count - 1 {
                    Divider().padding(.leading, 44)
                }
            }
        }
    }

    // MARK: - Floating Right Controls

    private var floatingMapControls: some View {
        VStack(spacing: 10) {
            Button {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                isSatelliteMode.toggle()
            } label: {
                Image(systemName: isSatelliteMode ? "map.fill" : "globe.americas.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(isSatelliteMode ? primaryColor : .primary)
                    .frame(width: 46, height: 46)
                    .background(.regularMaterial, in: Circle())
                    .shadow(color: .black.opacity(0.18), radius: 8, x: 0, y: 3)
            }
            .accessibilityLabel(isSatelliteMode ? "Switch to standard map" : "Switch to satellite map")

            if let userCoord = userCoordinate {
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    moveCamera(to: userCoord, spanDelta: 0.002)
                } label: {
                    Image(systemName: "location.fill")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(primaryColor)
                        .frame(width: 46, height: 46)
                        .background(.regularMaterial, in: Circle())
                        .shadow(color: .black.opacity(0.18), radius: 8, x: 0, y: 3)
                }
                .accessibilityLabel("Center on my current location")
            }

            if let origin = originCoordinate, movedDistanceMeters >= 10 {
                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    moveCamera(to: origin, spanDelta: 0.002)
                } label: {
                    Image(systemName: "arrow.uturn.backward")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(.orange)
                        .frame(width: 46, height: 46)
                        .background(.regularMaterial, in: Circle())
                        .shadow(color: .black.opacity(0.18), radius: 8, x: 0, y: 3)
                }
                .accessibilityLabel("Reset to original pin")
            }
        }
    }

    // MARK: - Bottom Confirmation Card

    private var bottomConfirmationCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                ZStack {
                    Circle()
                        .fill(primaryColor.opacity(0.14))
                        .frame(width: 42, height: 42)

                    Image(systemName: isDragging ? "scope" : "mappin.and.ellipse")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(primaryColor)
                }

                VStack(alignment: .leading, spacing: 3) {
                    if isDragging {
                        Text("Move map to position pin…")
                            .font(.system(size: 16, weight: .bold, design: .rounded))
                            .foregroundStyle(.secondary)
                    } else if geocodeHelper.isGeocoding && geocodeHelper.primaryAddress.isEmpty {
                        Text("Finding address…")
                            .font(.system(size: 16, weight: .bold, design: .rounded))
                            .foregroundStyle(.secondary)
                    } else {
                        Text(geocodeHelper.primaryAddress.isEmpty ? "Pinned Location" : geocodeHelper.primaryAddress)
                            .font(.system(size: 17, weight: .bold, design: .rounded))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                    }

                    if !isDragging && !geocodeHelper.secondaryAddress.isEmpty {
                        Text(geocodeHelper.secondaryAddress)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }

                    HStack(spacing: 8) {
                        Text(String(format: "%.6f, %.6f", centerCoordinate.latitude, centerCoordinate.longitude))
                            .font(.system(size: 12, weight: .semibold, design: .monospaced))
                            .foregroundStyle(.tertiary)

                        if let movedText = formattedDistanceMoved {
                            Text(movedText)
                                .font(.system(size: 11, weight: .bold, design: .rounded))
                                .foregroundStyle(isFarMoveOnTrainedLandmark ? .orange : primaryColor)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(
                                    (isFarMoveOnTrainedLandmark ? Color.orange : primaryColor).opacity(0.14),
                                    in: Capsule()
                                )
                        }
                    }
                    .padding(.top, 2)
                }

                Spacer(minLength: 0)
            }

            if isFarMoveOnTrainedLandmark {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 14))
                        .foregroundStyle(.orange)
                        .padding(.top, 1)

                    Text("Moving over 1 mile may place this landmark in a new cluster and require overnight AR retraining.")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(10)
                .background(Color.orange.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }

            if let saveErrorMessage {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                    Text(saveErrorMessage)
                        .font(.footnote.bold())
                        .foregroundStyle(.red)
                }
            }

            Button {
                UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                if isFarMoveOnTrainedLandmark {
                    showFarMoveAlert = true
                } else {
                    onConfirm(centerCoordinate.latitude, centerCoordinate.longitude, nil)
                }
            } label: {
                HStack(spacing: 8) {
                    if isSaving {
                        ProgressView().tint(.white)
                    } else {
                        Image(systemName: "checkmark.circle.fill")
                        Text(confirmButtonTitle)
                    }
                }
                .font(.system(size: 17, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background((isDragging || isSaving) ? Color.gray.opacity(0.45) : primaryColor)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(isDragging || isSaving)
        }
        .padding(.horizontal, 20)
        .padding(.top, 18)
        .padding(.bottom, 22)
        .background(.ultraThickMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .stroke(Color.primary.opacity(0.08), lineWidth: 1)
        }
        .shadow(color: .black.opacity(0.18), radius: 20, x: 0, y: -4)
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }

    // MARK: - Helpers

    private func dismissSearch() {
        isSearchFocused = false
        showSearchDropdown = false
    }

    private func selectSearchResult(_ completion: MKLocalSearchCompletion) {
        dismissSearch()
        searchModel.query = completion.title

        Task {
            if let coord = await searchModel.resolve(completion) {
                await MainActor.run {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    moveCamera(to: coord, spanDelta: 0.0022)
                }
            }
        }
    }

    private func moveCamera(to coord: CLLocationCoordinate2D, spanDelta: Double) {
        cameraTracker.cancel()
        centerCoordinate = coord
        withAnimation(.easeInOut(duration: 0.45)) {
            cameraPosition = .region(
                MKCoordinateRegion(
                    center: coord,
                    span: MKCoordinateSpan(latitudeDelta: spanDelta, longitudeDelta: spanDelta)
                )
            )
        }
        geocodeHelper.lookup(coordinate: coord, force: true)
    }
}
