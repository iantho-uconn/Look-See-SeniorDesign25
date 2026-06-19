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
    @State private var landmarkToUpload: NearbyLandmark?
    @State private var cameraPosition: MapCameraPosition = .userLocation(fallback: .automatic)
    @State private var showFilterSheet = false
    
    @State private var searchText: String = ""
    @State private var isGlobalSearch: Bool = true
    @State private var searchRadiusMiles: Double = 10.0
    @State private var myUploadsOnly = false
    @State private var promotedOnly = false
    @State private var selectedClusters: Set<String> = []
    
    private let primaryColor = Color(red: 0.11, green: 0.22, blue: 0.55)
    private let promoColor = Color.orange

    private var availableClusters: [String] {
        let clusters = nearbyService.items.compactMap { $0.clusterId }
        return Array(Set(clusters)).sorted()
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
        ZStack(alignment: .top) {
            Map(position: $cameraPosition) {
                UserAnnotation()
                ForEach(activeLandmarks) { landmark in
                    Annotation(landmark.label, coordinate: CLLocationCoordinate2D(latitude: landmark.latitude, longitude: landmark.longitude)) {
                        Button {
                            withAnimation(.spring()) { selectedLandmark = landmark }
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
                MapUserLocationButton()
                MapCompass()
            }
            .ignoresSafeArea(edges: .top)

            // Search Bar & Filter Container
            VStack(alignment: .trailing, spacing: 12) {
                // Polished Google-Maps Style Search Bar
                HStack {
                    Image(systemName: "magnifyingglass").foregroundStyle(.gray)
                    TextField("Search landmarks...", text: $searchText)
                        .foregroundStyle(.black) // Fixed: Black text on white background
                        .autocorrectionDisabled()
                        .submitLabel(.search)
                        .onSubmit {
                            if let firstMatch = activeLandmarks.first {
                                withAnimation(.easeInOut(duration: 1.0)) {
                                    cameraPosition = .region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: firstMatch.latitude, longitude: firstMatch.longitude), span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)))
                                }
                            }
                        }
                    if !searchText.isEmpty { Button { searchText = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.gray) } }
                }
                .padding(15)
                .background(Color.white) // Fixed: Bright white background
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .shadow(color: .black.opacity(0.2), radius: 6, x: 0, y: 3)
                .padding(.horizontal, 20)
                
                Button { showFilterSheet = true } label: {
                    ZStack(alignment: .topTrailing) {
                        Image(systemName: "slider.horizontal.3").font(.system(size: 18, weight: .semibold)).foregroundStyle(primaryColor).padding(12).background(Color.white).clipShape(Circle()).shadow(color: .black.opacity(0.2), radius: 5, x: 0, y: 2)
                        if myUploadsOnly || promotedOnly || !selectedClusters.isEmpty {
                            Circle().fill(promoColor).frame(width: 12, height: 12).overlay(Circle().stroke(.white, lineWidth: 2)).offset(x: -2, y: 2)
                        }
                    }
                }
                .padding(.trailing, 20)
            }
            .padding(.top, 65)
        }
        .task {
            if !locationManager.isAuthorized { locationManager.requestPermissionIfNeeded() }
            await vm.fetchUserEmail()
            await fetchMapData()
        }
        .sheet(item: $selectedLandmark) { landmark in
            landmarkDetailSheet(landmark).presentationDetents([.height(landmark.promotionEnabled ? 320 : 250)]).presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showFilterSheet, onDismiss: { Task { await fetchMapData() } }) {
            filterMenuSheet.presentationDetents([.fraction(0.85)])
        }
        .fullScreenCover(item: $landmarkToUpload) { landmark in
            QuickUploadView(landmark: landmark)
        }
    }

    private var filterMenuSheet: some View {
        NavigationStack {
            ZStack {
                Color(red: 0.06, green: 0.06, blue: 0.10).ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 24) {
                        VStack(alignment: .leading, spacing: 16) {
                            Text("Search Radius").font(.headline).foregroundStyle(.white)
                            Toggle("Global Search (Everywhere)", isOn: $isGlobalSearch).tint(primaryColor).foregroundStyle(.white)
                            if !isGlobalSearch {
                                Divider().background(Color.white.opacity(0.1))
                                HStack { Text("Distance:").foregroundStyle(.white); Spacer(); TextField("Miles", value: $searchRadiusMiles, format: .number).keyboardType(.decimalPad).textFieldStyle(.roundedBorder).frame(width: 80).multilineTextAlignment(.trailing); Text("mi").foregroundStyle(.gray) }
                                Slider(value: $searchRadiusMiles, in: 1...100, step: 1).tint(primaryColor)
                            }
                        }.padding().background(Color.white.opacity(0.05)).clipShape(RoundedRectangle(cornerRadius: 16))
                        VStack(alignment: .leading, spacing: 16) {
                            Text("Visibility").font(.headline).foregroundStyle(.white)
                            Toggle("My Uploads Only", isOn: $myUploadsOnly).tint(primaryColor).foregroundStyle(.white)
                            Divider().background(Color.white.opacity(0.1))
                            Toggle("Promoted Only", isOn: $promotedOnly).tint(promoColor).foregroundStyle(.white)
                        }.padding().background(Color.white.opacity(0.05)).clipShape(RoundedRectangle(cornerRadius: 16))
                        if !availableClusters.isEmpty {
                            VStack(alignment: .leading, spacing: 16) {
                                HStack { Text("Filter by Cluster").font(.headline).foregroundStyle(.white); Spacer(); if !selectedClusters.isEmpty { Button("Clear") { selectedClusters.removeAll() }.font(.caption.bold()).foregroundStyle(promoColor) } }
                                ForEach(availableClusters, id: \.self) { clusterId in
                                    Button { if selectedClusters.contains(clusterId) { selectedClusters.remove(clusterId) } else { selectedClusters.insert(clusterId) } } label: {
                                        HStack { Text("Cluster \(clusterId)").foregroundStyle(.white); Spacer(); if selectedClusters.contains(clusterId) { Image(systemName: "checkmark.circle.fill").foregroundStyle(primaryColor) } else { Image(systemName: "circle").foregroundStyle(.gray) } }
                                    }
                                    if clusterId != availableClusters.last { Divider().background(Color.white.opacity(0.1)) }
                                }
                            }.padding().background(Color.white.opacity(0.05)).clipShape(RoundedRectangle(cornerRadius: 16))
                        }
                    }.padding(20)
                }
            }
            .navigationTitle("Map Filters").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Apply") { showFilterSheet = false }.foregroundStyle(primaryColor) } }
            .toolbarBackground(Color(red: 0.06, green: 0.06, blue: 0.10), for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
        }
    }

    private func landmarkDetailSheet(_ landmark: NearbyLandmark) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            if landmark.promotionEnabled {
                HStack { Image(systemName: "sparkles"); Text(landmark.promotion ?? "Special Promotion Available!").font(.subheadline.bold()); Spacer() }.padding(12).background(promoColor.opacity(0.2)).foregroundStyle(promoColor).clipShape(RoundedRectangle(cornerRadius: 10))
            }
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(landmark.label).font(.title2.bold()).foregroundStyle(.white)
                    Text("\(String(format: "%.1f", landmark.distanceMeters / 1609.34)) miles away").font(.subheadline).foregroundStyle(primaryColor).bold()
                }
                Spacer()
            }
            Text(landmark.shortDescription).font(.body).foregroundStyle(Color.white.opacity(0.8))
            Spacer()
            HStack(spacing: 12) {
                Button { openAppleMaps(for: landmark) } label: {
                    HStack { Image(systemName: "location.fill"); Text("Directions").fontWeight(.semibold) }.frame(maxWidth: .infinity).padding(.vertical, 14).background(Color.gray.opacity(0.3)).foregroundStyle(.white).clipShape(RoundedRectangle(cornerRadius: 15))
                }
                Button {
                    let passedLandmark = landmark
                    selectedLandmark = nil
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { landmarkToUpload = passedLandmark }
                } label: {
                    HStack { Image(systemName: "camera.fill"); Text("Upload Data").fontWeight(.semibold) }.frame(maxWidth: .infinity).padding(.vertical, 14).background(primaryColor).foregroundStyle(.white).clipShape(RoundedRectangle(cornerRadius: 15))
                }
            }.padding(.bottom, 10)
        }.padding(24).background(Color(red: 0.06, green: 0.06, blue: 0.10).ignoresSafeArea())
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
