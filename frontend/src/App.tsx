import { Route, Routes } from "react-router";
import { AppLayout } from "@/components/app-layout";
import { LibraryPage } from "@/pages/library-page";
import { NotFoundPage } from "@/pages/not-found-page";
import { RidePage } from "@/pages/ride-page";
import { TodayPage } from "@/pages/today-page";

function App() {
    return (
        <Routes>
            <Route element={<AppLayout />}>
                <Route index element={<TodayPage />} />
                <Route path="library" element={<LibraryPage />} />
                <Route path="ride" element={<RidePage />} />
                <Route path="*" element={<NotFoundPage />} />
            </Route>
        </Routes>
    );
}

export default App;
