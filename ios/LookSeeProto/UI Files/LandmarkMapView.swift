//
//  LandmarkMapView.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 6/19/26.
//

import SwiftUI
import MapKit
import CoreLocation

struct LandmarkMapView: View {
    @EnvironmentObject var vm: AuthViewModel
    
    @StateObject private var nearbyService = NearbyLandmarkService()
    @StateObject private var locationManager = LocationManager()
    
    @State private var selectedLandmark: NearbyLandmark?
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
    private let mapBottomReservedHeight: CGFloat = 50

    private var availableClusters: [String] {
        let clusters = nearbyService.items.compactMap { $0.clusterId }
        let unique = Array(Set(clusters))

        return unique.sorted { lhs, rhs in
            if let lhsNum = Int(lhs), let rhsNum = Int(rhs) {
                return lhsNum < rhsNum
            }
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
                                withAnimation(.spring(response: 0.4, dampingFraction: 0.7)) {
                                    selectedLandmark = landmark
                                }
                                
                                // 🚀 THE FIX: Passing the full rich UI variables to the popup!
                                VariableContainer.shared.presentMapLandmark(
                                    id: landmark.id,
                                    name: landmark.label,
                                    description: landmark.shortDescription,
                                    latitude: landmark.latitude,
                                    longitude: landmark.longitude,
                                    promotionEnabled: landmark.promotionEnabled,
                                    promotion: landmark.promotion,
                                    ownerId: landmark.createdBy,
                                    websiteUrl: landmark.websiteUrl,
                                    promoName: landmark.promoName,
                                    promoDescription: landmark.promoDescription,
                                    promoImageUrl: landmark.promoImageUrl,
                                    merchantName: landmark.merchantName,
                                    merchantBio: landmark.merchantBio,
                                    merchantPhone: landmark.merchantPhone,
                                    merchantAddress: landmark.merchantAddress,
                                    merchantLogoUrl: landmark.merchantLogoUrl
                                )
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
                .mapControls {}
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
                .padding(.top, proxy.safeAreaInsets.top + topChromeReservedHeight)
            }
            .ignoresSafeArea(edges: .top)
        }
        .task {
            if !locationManager.isAuthorized { locationManager.requestPermissionIfNeeded() }
            await vm.fetchUserEmail()
            await fetchMapData()
        }
        .sheet(isPresented: $showFilterSheet, onDismiss: { Task { await fetchMapData() } }) {
            filterMenuSheet
                .presentationDetents([.fraction(0.85)])
        }
        // Deselects the pin smoothly when the master PopUp closes
        .onChange(of: VariableContainer.shared.infoView) { _, isPopUpOpen in
            if !isPopUpOpen {
                withAnimation(.spring(response: 0.4, dampingFraction: 0.7)) {
                    selectedLandmark = nil
                }
            }
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
                    IsKeyboard = false
                }
            )
            .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("Map Filters")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Apply") { showFilterSheet = false }.font(.system(size: 16, weight: .bold)).foregroundStyle(primaryColor) } }
        }
    }

    private func fetchMapData() async {
        guard locationManager.isAuthorized, let lat = locationManager.latitude, let lon = locationManager.longitude else { return }
        let meters = (isGlobalSearch ? 50000.0 : searchRadiusMiles) * 1609.34
        await nearbyService.fetchNearby(latitude: lat, longitude: lon, radiusMeters: meters)
    }
}
