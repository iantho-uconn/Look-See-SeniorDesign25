//
//  LandmarkMapView.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 6/19/26.
//
//  Fix: Map's Legal/attribution button was rendering behind the custom
//  bottom tab bar. Rather than depending on an ancestor view's
//  safeAreaInset propagating correctly (fragile across nested containers),
//  the Map now reserves its own bottom space directly via .safeAreaPadding,
//  so this is self-contained regardless of how Buttons.swift lays out its
//  pager/tab bar.
//

import SwiftUI
import MapKit
import CoreLocation

struct LandmarkMapView: View {
    @EnvironmentObject var vm: AuthViewModel
    
    @StateObject private var nearbyService = NearbyLandmarkService()
    @StateObject private var locationManager = LocationManager()
    
    @State private var selectedLandmark: NearbyLandmark?
    @State private var landmarkToUpload: NearbyLandmark?
    @State private var cameraPosition: MapCameraPosition = .userLocation(fallback: .automatic)
    @State private var showFilterSheet = false
    
    @State private var searchText: String = ""
    @State private var isGlobalSearch: Bool = true
    @State private var searchRadiusMiles: Double = 10.0
    @State private var myUploadsOnly = false
    @State private var promotedOnly = false
    @State private var selectedClusters: Set<String> = []
    
    @FocusState private var IsKeyboard: Bool
    
    private let topChromeReservedHeight: CGFloat = 80


    
    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)
    private let promoColor = Color.orange

    // Height reserved at the bottom of the map so MapKit's own Legal /
    // attribution control clears the custom bottom tab bar. Kept in sync
    // with the ~90pt safeAreaInset height Buttons.swift reserves for its
    // bottom bar, but applied here directly so this view doesn't depend on
    // that propagating correctly through any ancestor container.
    private let mapBottomReservedHeight: CGFloat = 50

    private var availableClusters: [String] {
        let clusters = nearbyService.items.compactMap { $0.clusterId }
        let unique = Array(Set(clusters))

        return unique.sorted { lhs, rhs in
            if let lhsNum = Int(lhs), let rhsNum = Int(rhs) {
                return lhsNum < rhsNum
            }
            // Fallback for any non-numeric cluster IDs, so this doesn't crash
            // or silently misorder if a cluster ID is ever a non-integer string.
            return lhs < rhs
        }
    }

    private var activeLandmarks: [NearbyLandmark] {
        nearbyService.items.filter { landmark in
            let matchesUser = myUploadsOnly ? (landmark.createdBy == vm.userEmail) : true
            let matchesPromo = promotedOnly ? landmark.promotionEnabled : true
            let matchesCluster = selectedClusters.isEmpty ? true : (landmark.clusterId != nil && selectedClusters.contains(landmark.clusterId!))
            let matchesSearch = searchText.isEmpty ? true : landmark.label.localizedCaseInsensitiveContains(searchText)
            
            return matchesUser && matchesPromo && matchesCluster && matchesSearch
        }
    }

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .top) {
                Map(position: $cameraPosition) {
                    UserAnnotation()
                    ForEach(activeLandmarks) { landmark in
                        Annotation(landmark.label, coordinate: CLLocationCoordinate2D(latitude: landmark.latitude, longitude: landmark.longitude)) {
                            Button {
                                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                withAnimation(.spring(response: 0.4, dampingFraction: 0.7)) { selectedLandmark = landmark }
                            } label: {
                                VStack(spacing: 0) {
                                    if landmark.promotionEnabled {
                                        Image(systemName: "crown.fill").font(.title3).foregroundStyle(.white).padding(8).background(Circle().fill(promoColor)).shadow(color: promoColor.opacity(0.8), radius: 6, x: 0, y: 2)
                                    } else {
                                        Image(systemName: "mappin.circle.fill").font(.title).foregroundStyle(.white, primaryColor).background(Circle().fill(.white)).shadow(color: .black.opacity(0.3), radius: 4, x: 0, y: 2)
                                    }
                                    Image(systemName: "triangle.fill").font(.caption2).foregroundStyle(landmark.promotionEnabled ? promoColor : primaryColor).rotationEffect(.degrees(180)).offset(y: -2)
                                }
                                .scaleEffect(selectedLandmark?.id == landmark.id ? 1.3 : 1.0)
                            }
                        }
                    }
                }
                .mapControls {
                }
                .safeAreaPadding(.bottom, mapBottomReservedHeight)
                .ignoresSafeArea(edges: .top)

                VStack(alignment: .trailing, spacing: 16) {
                    HStack(spacing: 12) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(primaryColor)
                        
                        TextField("", text: $searchText, prompt: Text("Search landmarks...").foregroundStyle(.secondary))
                            .font(.system(size: 16, weight: .medium, design: .rounded))
                            .foregroundStyle(.primary)
                            .autocorrectionDisabled()
                            .submitLabel(.search)
                            .onSubmit {
                                if let firstMatch = activeLandmarks.first {
                                    withAnimation(.easeInOut(duration: 1.0)) {
                                        cameraPosition = .region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: firstMatch.latitude, longitude: firstMatch.longitude), span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)))
                                    }
                                }
                            }
                        
                        if !searchText.isEmpty {
                            Button {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                searchText = ""
                            } label: {
                                Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .shadow(color: .black.opacity(0.15), radius: 15, x: 0, y: 5)
                    .padding(.horizontal, 20)
                    
                    Button {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        showFilterSheet = true
                    } label: {
                        ZStack(alignment: .topTrailing) {
                            Image(systemName: "slider.horizontal.3")
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundStyle(.primary)
                                .frame(width: 50, height: 50)
                                .background(.regularMaterial, in: Circle())
                                .shadow(color: .black.opacity(0.15), radius: 10, x: 0, y: 5)
                                
                            if myUploadsOnly || promotedOnly || !selectedClusters.isEmpty {
                                Circle().fill(promoColor).frame(width: 14, height: 14).overlay(Circle().stroke(Color(uiColor: .systemBackground), lineWidth: 2)).offset(x: -2, y: 2)
                            }
                        }
                    }
                    .padding(.trailing, 20)
                }
                // Dynamic top offset: device's own safe area (Dynamic Island vs
                // notch vs none) + the fixed 45pt Buttons.swift reserves as its
                // top safeAreaInset + the topBar's own rendered height + a
                // small buffer, instead of one hardcoded constant that only
                // matched one specific device.
                .padding(.top, proxy.safeAreaInsets.top + topChromeReservedHeight)
            }
            .ignoresSafeArea(edges: .top)
        }
        .task {
            if !locationManager.isAuthorized { locationManager.requestPermissionIfNeeded() }
            await vm.fetchUserEmail()
            await fetchMapData()
        }
        .sheet(item: $selectedLandmark) { landmark in
            landmarkDetailSheet(landmark)
                .presentationDetents([.height(landmark.promotionEnabled ? 340 : 260)])
                .presentationDragIndicator(.visible)
                .presentationBackground(.regularMaterial)
        }
        .sheet(isPresented: $showFilterSheet, onDismiss: { Task { await fetchMapData() } }) {
            filterMenuSheet
                .presentationDetents([.fraction(0.85)])
        }
        .fullScreenCover(item: $landmarkToUpload) { landmark in
            QuickUploadView(landmark: landmark)
        }
    }
    
    private var filterMenuSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("Search Radius").font(.system(size: 14, weight: .bold, design: .rounded)).foregroundStyle(.gray).textCase(.uppercase)
                        Toggle("Global Search (Everywhere)", isOn: $isGlobalSearch).tint(primaryColor).foregroundStyle(.primary).font(.system(size: 16, weight: .semibold))
                        if !isGlobalSearch {
                            Divider()
                            HStack { Text("Distance:").font(.system(size: 16, weight: .semibold)).foregroundStyle(.primary); Spacer();
                                TextField("Miles", value: $searchRadiusMiles, format: .number)
                                    .focused($IsKeyboard)
                                    .keyboardType(.decimalPad).textFieldStyle(.roundedBorder).frame(width: 80).multilineTextAlignment(.trailing); Text("mi").font(.system(size: 16, weight: .bold)).foregroundStyle(.gray) }
                            Slider(value: $searchRadiusMiles, in: 1...100, step: 1).tint(primaryColor)
                        }
                        
                    }
                    .contentShape(Rectangle())
                    .simultaneousGesture(
                        TapGesture().onEnded {
                            print("tap detected please work inside if ")
                            IsKeyboard = false
                        }
                    ).padding(20).background(Color(uiColor: .secondarySystemGroupedBackground)).clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                    
                    VStack(alignment: .leading, spacing: 16) {
                        Text("Visibility").font(.system(size: 14, weight: .bold, design: .rounded)).foregroundStyle(.gray).textCase(.uppercase)
                        Toggle("My Uploads Only", isOn: $myUploadsOnly).tint(primaryColor).foregroundStyle(.primary).font(.system(size: 16, weight: .semibold))
                        Divider()
                        Toggle("Promoted Only", isOn: $promotedOnly).tint(promoColor).foregroundStyle(.primary).font(.system(size: 16, weight: .semibold))
                    }.padding(20).background(Color(uiColor: .secondarySystemGroupedBackground)).clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                    
                    if !availableClusters.isEmpty {
                        VStack(alignment: .leading, spacing: 16) {
                            HStack { Text("Filter by Cluster").font(.system(size: 14, weight: .bold, design: .rounded)).foregroundStyle(.gray).textCase(.uppercase); Spacer(); if !selectedClusters.isEmpty { Button("Clear") { UIImpactFeedbackGenerator(style: .light).impactOccurred(); selectedClusters.removeAll() }.font(.caption.bold()).foregroundStyle(promoColor) } }
                            ForEach(availableClusters, id: \.self) { clusterId in
                                Button {
                                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                    if selectedClusters.contains(clusterId) { selectedClusters.remove(clusterId) } else { selectedClusters.insert(clusterId) }
                                } label: {
                                    HStack { Text("Cluster \(clusterId)").font(.system(size: 16, weight: .semibold)).foregroundStyle(.primary); Spacer(); if selectedClusters.contains(clusterId) { Image(systemName: "checkmark.circle.fill").foregroundStyle(primaryColor) } else { Image(systemName: "circle").foregroundStyle(.gray) } }
                                }
                                if clusterId != availableClusters.last { Divider() }
                            }
                        }.padding(20).background(Color(uiColor: .secondarySystemGroupedBackground)).clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                    }
                }.padding(20)
            }
            .contentShape(Rectangle())
            .simultaneousGesture(
                TapGesture().onEnded {
                    print("tap detected please work")
                    IsKeyboard = false
                }
            )
            .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("Map Filters")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Apply") { showFilterSheet = false }.font(.system(size: 16, weight: .bold)).foregroundStyle(primaryColor) } }
        }
    }

    private func landmarkDetailSheet(_ landmark: NearbyLandmark) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            if landmark.promotionEnabled {
                HStack {
                    Image(systemName: "sparkles")
                    Text(landmark.promotion ?? "Special Promotion Available!")
                        .font(.system(size: 14, weight: .bold, design: .rounded))
                    Spacer()
                }
                .padding(12)
                .background(promoColor.opacity(0.2))
                .foregroundStyle(promoColor)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(landmark.label)
                        .font(.system(size: 24, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)
                    Text("\(String(format: "%.1f", landmark.distanceMeters / 1609.34)) miles away")
                        .font(.system(size: 14, weight: .bold, design: .rounded))
                        .foregroundStyle(primaryColor)
                }
                Spacer()
            }
            
            Text(landmark.shortDescription)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(.secondary)
            
            Spacer()
            
            HStack(spacing: 12) {
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    openAppleMaps(for: landmark)
                } label: {
                    HStack {
                        Image(systemName: "location.fill")
                        Text("Directions").fontWeight(.bold)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(Color(uiColor: .tertiarySystemFill))
                    .foregroundStyle(.primary)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                
                Button {
                    UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                    let passedLandmark = landmark
                    selectedLandmark = nil
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { landmarkToUpload = passedLandmark }
                } label: {
                    HStack {
                        Image(systemName: "arrow.up.circle.fill")
                        Text("Upload Data").fontWeight(.bold)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(primaryColor)
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
            .padding(.bottom, 10)
        }
        .padding(24)
    }

    private func fetchMapData() async {
        guard locationManager.isAuthorized, let lat = locationManager.latitude, let lon = locationManager.longitude else { return }
        let meters = (isGlobalSearch ? 50000.0 : searchRadiusMiles) * 1609.34
        await nearbyService.fetchNearby(latitude: lat, longitude: lon, radiusMeters: meters)
    }
    
    private func openAppleMaps(for landmark: NearbyLandmark) {
        let mapItem = MKMapItem(placemark: MKPlacemark(coordinate: CLLocationCoordinate2D(latitude: landmark.latitude, longitude: landmark.longitude)))
        mapItem.name = landmark.label
        mapItem.openInMaps(launchOptions: [MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeDriving])
    }
}
